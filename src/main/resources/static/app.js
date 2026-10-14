'use strict';

// Names are snake_case throughout. The camelCase names that remain (querySelector, addEventListener,
// textContent, classList, ...) belong to the browser's DOM API and cannot be renamed.

const POLL_MS = 2000;
const GROUP_MIN = 3; // this many identical siblings collapse into one "×N" row

const state = {
    view: 'requests',
    paused: false,
    traces: [],
    selected_id: null,
    detail: null,
    expanded_groups: new Set(),
    open_spans: new Set(),
    highlight: new Set(),
    active_issue: null,
    group_repeats: true, // a loop's repeated calls collapse into one "×N" row, even when interleaved
};

const $ = (selector) => document.querySelector(selector);

// ---- Formatting -------------------------------------------------------------------------------

const esc = (value) => {
    return String(value ?? '').replace(/[&<>"']/g, (c) => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
    })[c]);
}

const fmt_ms = (ms) => {
    if (ms == null) return '–';
    if (ms >= 1000) return (ms / 1000).toFixed(2) + ' s';
    if (ms >= 10) return Math.round(ms) + ' ms';
    if (ms < 0.1) return '<0.1 ms';
    return ms.toFixed(1) + ' ms';
}

const fmt_time = (epoch_ms) => {
    return new Date(epoch_ms).toLocaleTimeString([], { hour12: false });
}

/** "Session.find com.acme.user.User" -> "Session.find User" */
const short_names = (text) => {
    return String(text ?? '').replace(/\b(?:[a-z_][\w$]*\.)+([A-Z][\w$]*)/g, '$1');
}

const attrs_of = (node) => {
    if (!node._attrs) {
        try {
            node._attrs = JSON.parse(node.raw_attrs || '{}');
        } catch {
            node._attrs = {};
        }
    }
    return node._attrs;
}

/** Bind values the agent recorded as db.query.parameter.0, .1, ... (needs capture-query-parameters=true). */
const sql_params = (node) => {
    const params = [];
    for (const [key, value] of Object.entries(attrs_of(node))) {
        const m = key.match(/^db\.query\.parameter\.(\d+)$/);
        if (m) params[Number(m[1])] = value;
    }
    return params;
}

const sql_literal = (value) => {
    if (value === null || value === undefined) return 'NULL';
    const text = String(value);
    return /^-?\d+(\.\d+)?$/.test(text) || text === 'true' || text === 'false' ? text : `'${text.replace(/'/g, "''")}'`;
}

/** The SQL with each ? replaced by the value that was bound to it; string literals are left alone. */
const fill_sql = (sql, params) => {
    if (!sql || params.length === 0) return sql;
    let i = 0;
    return sql.replace(/'[^']*(?:''[^']*)*'|\?/g, (token) => {
        if (token !== '?') return token;
        const value = params[i++];
        return value === undefined ? '?' : sql_literal(value);
    });
}

const pretty_body = (text) => {
    try {
        return JSON.stringify(JSON.parse(text), null, 2);
    } catch {
        return text;
    }
}

const status_class = (code) => {
    if (code == null) return '';
    if (code >= 500) return 'err';
    if (code >= 400) return 'warn';
    return 'ok';
}

// ---- Storage of UI preferences (best effort) ---------------------------------------------------

const load_prefs = () => {
    try {
        const prefs = JSON.parse(localStorage.getItem('logss.prefs') || '{}');
        $('#issues_only').checked = !!prefs.issues_only;
        $('#hide_warmup').checked = !!prefs.hide_warmup;
        $('#hide_noise').checked = prefs.hide_noise !== false;
    } catch {
        // storage unavailable: defaults are fine
    }
}

const save_prefs = () => {
    try {
        localStorage.setItem('logss.prefs', JSON.stringify({
            issues_only: $('#issues_only').checked,
            hide_warmup: $('#hide_warmup').checked,
            hide_noise: $('#hide_noise').checked,
        }));
    } catch {
        // ignore
    }
}

// ---- API ------------------------------------------------------------------------------------

const api = async (path, options) => {
    const response = await fetch(path, options);
    if (!response.ok) throw new Error(`${response.status} ${response.statusText}`);
    return response.status === 204 ? null : response.json();
}

const set_status = (ok, text) => {
    const el = $('#status');
    el.classList.toggle('live', ok && !state.paused);
    el.classList.toggle('offline', !ok);
    $('#status_text').textContent = text;
}

const refresh_stats = async () => {
    const s = await api('/api/stats');
    const parts = [`${s.traces} requests`];
    if (s.open > 0) parts.push(`${s.open} in progress`);
    set_status(true, (state.paused ? 'Paused · ' : 'Live · ') + parts.join(' · '));
}

// ---- Request list -----------------------------------------------------------------------------

const load_traces = async () => {
    const params = new URLSearchParams({
        q: $('#filter').value,
        issues_only: $('#issues_only').checked,
        hide_warmup: $('#hide_warmup').checked,
        hide_noise: $('#hide_noise').checked,
        limit: 300,
    });
    state.traces = await api('/api/traces?' + params);
    render_traces();
}

const render_traces = () => {
    const rows = state.traces.map((t) => {
        const is_http = t.kind === 'http';
        const label = is_http ? (t.route || t.path || t.name) : t.name;
        const standalone_sql = t.kind === 'job' && t.span_count === t.db_count;
        const chip = is_http && t.method
            ? `<span class="chip method">${esc(t.method)}</span>`
            : `<span class="chip job">${esc(t.kind === 'message' ? 'MSG' : standalone_sql ? 'SQL' : 'JOB')}</span>`;
        const show_path = is_http && t.path && t.path !== t.route;
        const status = t.status_code != null
            ? `<span class="code ${status_class(t.status_code)}">${t.status_code}</span>`
            : `<span class="code ${t.error_count > 0 ? 'err' : 'ok'}">${t.error_count > 0 ? 'error' : 'ok'}</span>`;
        const issue_inner = `<span class="sev ${esc(t.top_severity)}"></span><span>${esc(t.top_issue)}</span>${
            t.issue_count > 1 ? `<span class="more">+${t.issue_count - 1}</span>` : ''}`;
        const issue = t.issue_count > 0 ? `<div class="issue_cell">${issue_inner}</div>` : '';
        const issue_inline = t.issue_count > 0 ? `<div class="issue_cell issue_inline">${issue_inner}</div>` : '';
        const tags = (t.is_warmup ? ' <span class="tag">warm-up</span>' : '') + (t.has_root ? '' : ' <span class="tag">no root</span>');
        return `<tr data-id="${esc(t.trace_id)}" class="${t.trace_id === state.selected_id ? 'selected' : ''} ${t.is_warmup ? 'warmup' : ''}">
            <td class="time">${fmt_time(t.start_ms)}</td>
            <td class="req_cell"><div class="req">${chip}<span class="name">${esc(label)}</span>${tags}</div>${
                show_path ? `<div class="req_path">${esc(t.path)}</div>` : ''}${issue_inline}</td>
            <td>${status}</td>
            <td class="num">${fmt_ms(t.duration_ms)}</td>
            <td class="num">${t.db_count}</td>
            <td class="issue_col">${issue}</td>
        </tr>`;
    });
    $('#trace_rows').innerHTML = rows.join('');

    const empty = $('#empty');
    const filtered = $('#filter').value || $('#issues_only').checked || $('#hide_warmup').checked;
    empty.hidden = state.traces.length > 0;
    empty.innerHTML = filtered
        ? 'No requests match the filters.'
        : `<h3>Waiting for traces</h3>
           <p>Start your app with the OpenTelemetry Java agent and send traces here:</p>
           <pre>export OTEL_SERVICE_NAME=my-app
export OTEL_EXPORTER_OTLP_ENDPOINT=${esc(location.origin)}
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
export OTEL_METRICS_EXPORTER=none
export OTEL_LOGS_EXPORTER=none
# Show the values bound to each ? in SQL
export OTEL_INSTRUMENTATION_JDBC_EXPERIMENTAL_CAPTURE_QUERY_PARAMETERS=true
# Request and response bodies (needs the logss-spring dependency)
export LOGSS_CAPTURE_ENABLED=true

java -javaagent:/path/to/opentelemetry-javaagent.jar -jar app.jar</pre>
           <p>Then call a few endpoints. Requests appear a couple of seconds after they finish.</p>`;
}

// ---- Detail -----------------------------------------------------------------------------------

const select_trace = async (trace_id) => {
    state.selected_id = trace_id;
    state.expanded_groups.clear();
    state.open_spans.clear();
    state.highlight.clear();
    state.active_issue = null;
    history.replaceState(null, '', '#trace=' + trace_id);
    document.querySelectorAll('#trace_rows tr').forEach((tr) => tr.classList.toggle('selected', tr.dataset.id === trace_id));
    try {
        state.detail = await api('/api/traces/' + encodeURIComponent(trace_id));
        render_detail();
    } catch (e) {
        $('#detail').innerHTML = `<p class="placeholder">This request is no longer stored.</p>`;
    }
}

const render_detail = () => {
    const { trace: t, spans, issues } = state.detail;
    const is_http = t.kind === 'http';
    const title = is_http && t.method ? `${t.method} ${t.route || t.path || ''}` : t.name;
    const sub = is_http && t.path ? t.path + (t.query ? '?' + t.query : '') : t.service;

    const stats = [
        ['Duration', fmt_ms(t.duration_ms)],
        ['Spans', t.span_count],
        ['Queries', `${t.db_count}${t.db_count ? ` · ${fmt_ms(t.db_time_ms)}` : ''}`],
        ['HTTP calls', t.http_out_count],
        ['Errors', t.error_count],
    ].map(([k, v]) => `<div class="stat"><div class="v">${esc(v)}</div><div class="k">${k}</div></div>`).join('');

    const notes = [];
    if (t.status_code != null) notes.push(`<span class="code ${status_class(t.status_code)}">HTTP ${t.status_code}</span>`);
    if (t.is_warmup) notes.push('<span class="tag">warm-up: one of the first calls after start, timings run high</span>');
    if (!t.has_root) notes.push('<span class="tag">root span missing</span>');

    const issue_cards = issues.length
        ? issues.map((issue, i) => {
            const meta = [
                issue.time_ms != null ? fmt_ms(issue.time_ms) : null,
                issue.share_pct != null
                    ? `${issue.share_pct < 1 ? '<1' : Math.round(issue.share_pct)}% of the ${t.kind === 'http' ? 'request' : 'job'}`
                    : null,
            ].filter(Boolean).join(' · ');
            return `<div class="issue ${esc(issue.severity)} ${state.active_issue === i ? 'active' : ''}" data-issue="${i}">
                <div class="title">${esc(issue.title)}</div>
                ${meta ? `<div class="meta">${esc(meta)}</div>` : ''}
                ${issue.code_function ? `<div class="where">${esc(issue.code_function)}</div>` : ''}
                ${issue.suggestion ? `<div class="fix">${esc(issue.suggestion)}</div>` : ''}
            </div>`;
        }).join('')
        : '<p class="placeholder" style="padding:0">No issues found.</p>';

    $('#detail').innerHTML = `<div class="detail">
        <h2>${esc(title)}</h2>
        <div class="sub">${esc(sub)} · ${fmt_time(t.start_ms)} · <span class="trace_id" title="Copy trace ID">${esc(t.trace_id.slice(0, 16))}</span></div>
        <div class="stats">${stats}</div>
        ${notes.length ? `<div class="notes">${notes.join('')}</div>` : ''}
        <div class="section_title">Issues</div>
        ${issue_cards}
        ${is_http ? render_bodies(spans) : ''}
        <div class="section_head"><div class="section_title">Timeline</div>
            <label class="check"><input type="checkbox" id="group_repeats" ${state.group_repeats ? 'checked' : ''}> Group repeated calls</label></div>
        <div class="waterfall">${render_waterfall(spans, t.duration_ms)}</div>
    </div>`;
}

/** Request and response bodies, recorded on the server span by the logss-spring library. */
const render_bodies = (spans) => {
    const server = spans.find((s) => s.category === 'http_server');
    const attrs = server ? attrs_of(server) : {};
    const request = attrs['http.request.body'];
    const response = attrs['http.response.body'];
    if (request == null && response == null) {
        return `<div class="section_title">Request and response</div>
            <p class="hint_inline">Bodies aren't captured for this app. Add the <code>logss-spring</code> dependency
            and start the app with <code>LOGSS_CAPTURE_ENABLED=true</code>.</p>`;
    }
    const block = (title, body) => `<div class="body_block"><div class="lbl">${title}</div>${
        body == null ? '<p class="hint_inline">No body</p>' : `<pre>${esc(pretty_body(body))}</pre>`}</div>`;
    return `<div class="section_title">Request and response</div>
        <div class="bodies">${block('Request body', request)}${block('Response body', response)}</div>`;
}

/** Builds the span tree and folds each Hibernate span into its single SQL child. */
const build_tree = (spans) => {
    const nodes = new Map(spans.map((s) => [s.span_id, { ...s, children: [] }]));
    const roots = [];
    for (const node of nodes.values()) {
        const parent = node.parent_span_id && nodes.get(node.parent_span_id);
        (parent ? parent.children : roots).push(node);
    }
    const visit = (node) => {
        node.children.sort((a, b) => a.offset_ms - b.offset_ms);
        node.children.forEach(visit);
        const only = node.children.length === 1 ? node.children[0] : null;
        if ((node.scope_name || '').includes('hibernate') && only && only.category === 'db' && only.children.length === 0) {
            node.merged = only;
            node.children = [];
        }
    };
    roots.sort((a, b) => a.offset_ms - b.offset_ms);
    roots.forEach(visit);
    return roots;
}

const signature = (node) => {
    return node.name + '|' + ((node.merged && node.merged.sql_shape) || node.sql_shape || '');
}

/**
 * Splits siblings into runs. Grouped: every sibling with the same signature joins one run at its first
 * position, so a loop body (A, B, C, A, B, C, ...) reads as A ×N, B ×N, C ×N. Otherwise only
 * back-to-back repeats are merged and time order is kept exactly.
 */
const runs_of = (nodes) => {
    if (state.group_repeats) {
        const runs = new Map();
        for (const node of nodes) {
            const key = signature(node);
            if (!runs.has(key)) runs.set(key, []);
            runs.get(key).push(node);
        }
        return [...runs.values()];
    }
    const runs = [];
    for (const node of nodes) {
        const last = runs[runs.length - 1];
        if (last && signature(last[0]) === signature(node)) last.push(node);
        else runs.push([node]);
    }
    return runs;
}

const flatten = (nodes, depth, out) => {
    for (const run of runs_of(nodes)) {
        if (run.length >= GROUP_MIN) {
            const id = 'g_' + run[0].span_id;
            const open = state.expanded_groups.has(id);
            out.push({ type: 'group', id, depth, nodes: run, open });
            if (open) run.forEach((node) => push_node(node, depth + 1, out));
        } else {
            run.forEach((node) => push_node(node, depth, out));
        }
    }
}

const push_node = (node, depth, out) => {
    out.push({ type: 'span', depth, node });
    flatten(node.children, depth + 1, out);
}

const has_error = (node) => {
    return node.status === 'error' || (node.merged && node.merged.status === 'error');
}

const bar = (offset, duration, total, cls) => {
    const left = total > 0 ? Math.min(100, (offset / total) * 100) : 0;
    const width = total > 0 ? Math.max(0.3, Math.min(100 - left, (duration / total) * 100)) : 0;
    return `<div class="wf_bar"><div class="bar ${cls}" style="left:${left}%;width:${width}%"></div></div>`;
}

const span_label = (node) => {
    const sql_node = node.merged || node;
    let name = node.code_function || short_names(node.name);
    let extra = '';
    if (node.category === 'http_client') {
        name = `${node.http_method || ''} ${node.http_host || node.name}`.trim();
        if (node.http_status != null) name += ` → ${node.http_status}`;
        extra = node.http_url ? `<div class="wf_extra">${esc(node.http_url)}</div>` : '';
    } else if (node.category === 'http_server' && node.http_status != null) {
        name += ` → ${node.http_status}`;
    } else if (node.category === 'db' && !node.sql) {
        name += ' · no SQL recorded';
    }
    const sql = sql_node.sql ? `<div class="wf_sql">${esc(fill_sql(sql_node.sql, sql_params(sql_node)))}</div>` : '';
    const err_node = node.status === 'error' ? node : node.merged;
    // An HTTP error whose only detail is the status code already shows as "→ 400" in the name.
    const only_status = err_node && !err_node.error_message && err_node.error_type === String(err_node.http_status);
    const err = err_node && err_node.status === 'error' && !only_status && (err_node.error_message || err_node.error_type)
        ? `<div class="wf_extra err">${esc([err_node.error_type, err_node.error_message].filter(Boolean).join(': '))}</div>`
        : '';
    return { name, body: sql + extra + err };
}

const render_waterfall = (spans, trace_ms) => {
    const total = trace_ms || Math.max(0, ...spans.map((s) => s.offset_ms + s.duration_ms));
    const rows = [];
    flatten(build_tree(spans), 0, rows);

    return rows.map((row) => {
        if (row.type === 'group') {
            const first = row.nodes[0];
            const start = Math.min(...row.nodes.map((n) => n.offset_ms));
            const end = Math.max(...row.nodes.map((n) => n.offset_ms + n.duration_ms));
            const sum = row.nodes.reduce((acc, n) => acc + n.duration_ms, 0);
            const errors = row.nodes.filter(has_error).length;
            const hl = row.nodes.some((n) => state.highlight.has(n.span_id) || (n.merged && state.highlight.has(n.merged.span_id)));
            const label = span_label(first);
            const cat = (first.merged || first).category;
            return `<div class="wf_row wf_group ${errors ? 'err' : ''} ${hl ? 'hl' : ''}" data-group="${esc(row.id)}" style="--depth:${row.depth}">
                <div class="wf_label"><div class="wf_name"><span class="caret">${row.open ? '▾' : '▸'}</span>
                    <span class="cat cat_${esc(cat)}"></span>
                    <span class="txt">${esc(label.name)} <span class="count">×${row.nodes.length}</span>${
                        errors ? ` · <span class="code err">${errors === row.nodes.length ? 'all' : errors} failed</span>` : ''}</span></div>${label.body}</div>
                ${bar(start, end - start, total, 'cat_' + cat + (errors === row.nodes.length ? ' err' : ''))}
                <div class="wf_dur" title="Total time of all ${row.nodes.length}">Σ ${fmt_ms(sum)}</div>
            </div>`;
        }
        const node = row.node;
        const ids = [node.span_id, node.merged && node.merged.span_id].filter(Boolean);
        const hl = ids.some((id) => state.highlight.has(id));
        const label = span_label(node);
        const cat = (node.merged || node).category;
        const html = `<div class="wf_row ${has_error(node) ? 'err' : ''} ${hl ? 'hl' : ''}" data-span="${esc(node.span_id)}" style="--depth:${row.depth}">
            <div class="wf_label"><div class="wf_name"><span class="caret"></span><span class="cat cat_${esc(cat)}"></span>
                <span class="txt">${esc(label.name)}</span></div>${label.body}</div>
            ${bar(node.offset_ms, node.duration_ms, total, 'cat_' + cat + (has_error(node) ? ' err' : ''))}
            <div class="wf_dur">${fmt_ms(node.duration_ms)}</div>
        </div>`;
        return state.open_spans.has(node.span_id) ? html + span_detail(node, row.depth) : html;
    }).join('');
}

const pretty_attrs = (raw) => {
    try {
        return JSON.stringify(JSON.parse(raw || '{}'), null, 2);
    } catch {
        return raw || '';
    }
}

const span_detail = (node, depth) => {
    const parts = [];
    for (const n of [node, node.merged].filter(Boolean)) {
        parts.push(`<div class="lbl">${esc(n.name)} · ${esc(n.scope_name || '')}</div>`);
        if (n.sql) {
            const params = sql_params(n);
            parts.push(`<pre>${esc(fill_sql(n.sql, params))}</pre>`);
            if (params.length) {
                parts.push(`<div class="lbl">Parameters</div><pre>${esc(params.map((p, i) => `${i + 1}: ${sql_literal(p)}`).join('\n'))}</pre>`);
            }
        }
        if (n.error_message || n.error_type) parts.push(`<pre>${esc([n.error_type, n.error_message].filter(Boolean).join(': '))}</pre>`);
        if (n.stacktrace) parts.push(`<div class="lbl">Stack trace</div><pre>${esc(n.stacktrace)}</pre>`);
        if (n.thread_name) parts.push(`<div class="lbl">Thread</div><pre>${esc(n.thread_name)}</pre>`);
        parts.push(`<div class="lbl">Raw attributes</div><pre>${esc(pretty_attrs(n.raw_attrs))}</pre>`);
    }
    return `<div class="wf_detail" style="--depth:${depth}">${parts.join('')}</div>`;
}

/** Highlights an issue's evidence spans and scrolls to the first one. */
const toggle_issue = (index) => {
    const issue = state.detail.issues[index];
    if (state.active_issue === index) {
        state.active_issue = null;
        state.highlight.clear();
    } else {
        state.active_issue = index;
        let ids = [];
        try {
            ids = JSON.parse(issue.span_ids || '[]');
        } catch {
            ids = [];
        }
        state.highlight = new Set(ids);
    }
    render_detail();
    const first = document.querySelector('.wf_row.hl');
    if (first) first.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
}

// ---- Queries view -----------------------------------------------------------------------------

const load_queries = async () => {
    const queries = await api('/api/queries?limit=100');
    $('#query_rows').innerHTML = queries.map((q) => {
        const routes = String(q.routes || '').split(',').filter(Boolean)
            .map((r) => `<span class="chip">${esc(r)}</span>`).join('');
        return `<tr>
            <td class="sql"><div class="clamp">${esc(q.normalized_sql)}</div></td>
            <td class="num">${q.runs}</td>
            <td class="num">${q.traces}</td>
            <td class="num">${fmt_ms(q.total_ms)}</td>
            <td class="num">${fmt_ms(q.avg_ms)}</td>
            <td class="num">${fmt_ms(q.max_ms)}</td>
            <td><div class="routes">${routes}</div></td>
        </tr>`;
    }).join('');
    $('#queries_empty').hidden = queries.length > 0;
}

// ---- Wiring -----------------------------------------------------------------------------------

const tick = async () =>  {
    if (state.paused || document.hidden) return;
    try {
        if (state.view === 'requests') await load_traces();
        else await load_queries();
        await refresh_stats();
    } catch (e) {
        set_status(false, 'Offline: is logss running?');
    }
}

const debounce = (fn, ms) => {
    let timer;
    return (...args) => {
        clearTimeout(timer);
        timer = setTimeout(() => fn(...args), ms);
    };
}

const show_view = (view) => {
    state.view = view;
    document.querySelectorAll('.tabs button').forEach((b) => b.classList.toggle('active', b.dataset.view === view));
    $('#view_requests').hidden = view !== 'requests';
    $('#view_queries').hidden = view !== 'queries';
    tick();
}

document.querySelectorAll('.tabs button').forEach((b) => b.addEventListener('click', () => show_view(b.dataset.view)));

$('#trace_rows').addEventListener('click', (e) => {
    const row = e.target.closest('tr[data-id]');
    if (row) select_trace(row.dataset.id);
});

$('#detail').addEventListener('change', (e) => {
    if (e.target.id === 'group_repeats') {
        state.group_repeats = e.target.checked;
        state.expanded_groups.clear();
        render_detail();
    }
});

$('#detail').addEventListener('click', (e) => {
    const issue = e.target.closest('.issue[data-issue]');
    if (issue) return toggle_issue(Number(issue.dataset.issue));

    const trace_id = e.target.closest('.trace_id');
    if (trace_id && state.detail) {
        navigator.clipboard?.writeText(state.detail.trace.trace_id);
        trace_id.textContent = 'copied';
        return;
    }

    const group = e.target.closest('.wf_row[data-group]');
    if (group) {
        const id = group.dataset.group;
        state.expanded_groups.has(id) ? state.expanded_groups.delete(id) : state.expanded_groups.add(id);
        return render_detail();
    }

    const span = e.target.closest('.wf_row[data-span]');
    if (span) {
        const id = span.dataset.span;
        state.open_spans.has(id) ? state.open_spans.delete(id) : state.open_spans.add(id);
        render_detail();
    }
});

$('#query_rows').addEventListener('click', (e) => {
    const cell = e.target.closest('td.sql');
    if (cell) cell.classList.toggle('open');
});

$('#filter').addEventListener('input', debounce(tick, 250));
['#issues_only', '#hide_warmup', '#hide_noise'].forEach((id) => $(id).addEventListener('change', () => {
    save_prefs();
    tick();
}));

$('#pause').addEventListener('click', () => {
    state.paused = !state.paused;
    $('#pause').textContent = state.paused ? 'Resume' : 'Pause';
    $('#pause').classList.toggle('on', state.paused);
    if (!state.paused) tick();
    else set_status(true, 'Paused');
});

$('#clear').addEventListener('click', async () => {
    if (!confirm('Delete all stored requests?')) return;
    await api('/api/traces', { method: 'DELETE' });
    state.selected_id = null;
    state.detail = null;
    $('#detail').innerHTML = '<p class="placeholder">Select a request to see what it did.</p>';
    tick();
});

document.addEventListener('visibilitychange', () => {
    if (!document.hidden) tick();
});

load_prefs();
tick();
setInterval(tick, POLL_MS);

// Links like http://localhost:4318/#trace=<id> open that request directly.
const linked = location.hash.match(/^#trace=([0-9a-f]+)$/);
if (linked) select_trace(linked[1]);
