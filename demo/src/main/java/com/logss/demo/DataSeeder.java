package com.logss.demo;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Fills the in-memory database on startup: 10 customers, 40 orders, about 160 items and a 1,500-row SKU catalog. */
@Component
class DataSeeder implements CommandLineRunner {
    private final CustomerRepository customers;
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final JdbcTemplate jdbc;

    DataSeeder(CustomerRepository customers, OrderRepository orders, OrderItemRepository items, JdbcTemplate jdbc) {
        this.customers = customers;
        this.orders = orders;
        this.items = items;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(String... args) {
        Random random = new Random(42);
        List<Customer> saved = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            saved.add(customers.save(new Customer("Customer " + i, "customer" + i + "@example.com")));
        }
        for (int i = 0; i < 40; i++) {
            ShopOrder order = orders.save(new ShopOrder(saved.get(random.nextInt(saved.size()))));
            int count = 3 + random.nextInt(3);
            for (int j = 0; j < count; j++) {
                items.save(new OrderItem(order, "SKU-" + (100 + random.nextInt(900)), 1 + random.nextInt(4),
                        BigDecimal.valueOf(5 + random.nextInt(95))));
            }
        }
        // A catalog with no index on code, for the slow report query.
        jdbc.execute("CREATE TABLE sku_catalog (id INT PRIMARY KEY, code VARCHAR(20))");
        jdbc.execute("INSERT INTO sku_catalog SELECT X, CONCAT('SKU-', X) FROM SYSTEM_RANGE(1, 1500)");
    }
}
