package com.logss.demo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Every endpoint here is written badly on purpose. The comment on each says what logss should report.
 */
@RestController
@RequestMapping("/api")
class ShopController {
    private final OrderRepository orders;
    private final ProductRepository products;
    private final JdbcTemplate jdbc;
    // Nothing listens on port 9, so every call fails, like a partner API that is down.
    private final RestClient partnerApi = RestClient.create("http://localhost:9");

    ShopController(OrderRepository orders, ProductRepository products, JdbcTemplate jdbc) {
        this.orders = orders;
        this.products = products;
        this.jdbc = jdbc;
    }

    record OrderSummary(Long id, String customer, int items) {
    }

    /** N+1: one query for the orders, then one for each order's customer and one for each order's items. */
    @GetMapping("/orders")
    @Transactional(readOnly = true)
    public List<OrderSummary> listOrders() {
        return orders.findAll().stream()
                .map(o -> new OrderSummary(o.id, o.customer.getName(), o.items.size()))
                .toList();
    }

    record ImportRequest(String supplier, int count, String apiKey) {
    }

    /**
     * save() in a loop on an entity whose ID the app sets: a SELECT before every INSERT, and one INSERT
     * per row. The request body's apiKey shows that logss-spring masks secrets.
     */
    @PostMapping("/products/import")
    @Transactional
    public Map<String, Object> importProducts(@RequestBody ImportRequest request) {
        int count = Math.min(Math.max(request.count(), 1), 100);
        for (int i = 1; i <= count; i++) {
            String sku = request.supplier() + "-" + i;
            products.save(new Product(sku, "Product " + sku, BigDecimal.valueOf(10 + i)));
        }
        return Map.of("imported", count, "supplier", request.supplier());
    }

    /** Repeated failures: three calls to a partner API that is down, each one failing the same way. */
    @PostMapping("/partners/sync")
    public Map<String, Object> syncPartners() {
        int failed = 0;
        for (String partner : List.of("north", "south", "east")) {
            try {
                partnerApi.post().uri("/sync/{partner}", partner).retrieve().toBodilessEntity();
            } catch (RestClientException e) {
                failed++;
            }
        }
        return Map.of("partners", 3, "failed", failed);
    }

    /** Slow query: a self-join on an unindexed LIKE, about 2 million comparisons. */
    @GetMapping("/reports/sku-overlap")
    public List<Map<String, Object>> skuOverlap() {
        return jdbc.queryForList("""
                SELECT a.code, COUNT(*) AS matches
                FROM sku_catalog a JOIN sku_catalog b ON b.code LIKE CONCAT(a.code, '%')
                GROUP BY a.code ORDER BY matches DESC LIMIT 5
                """);
    }

    /** A healthy endpoint for comparison: one query, no issues. */
    @GetMapping("/orders/{id}")
    public Map<String, Object> getOrder(@org.springframework.web.bind.annotation.PathVariable Long id) {
        ShopOrder order = orders.findById(id).orElseThrow();
        return Map.of("id", order.id);
    }
}
