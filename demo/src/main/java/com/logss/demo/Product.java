package com.logss.demo;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "product")
class Product {
    @Id
    String sku;
    String name;
    BigDecimal price;

    protected Product() {
    }

    Product(String sku, String name, BigDecimal price) {
        this.sku = sku;
        this.name = name;
        this.price = price;
    }
}
