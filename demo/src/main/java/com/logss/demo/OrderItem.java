package com.logss.demo;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "order_item")
class OrderItem {
    @Id
    @GeneratedValue
    Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    ShopOrder order;

    String sku;
    int quantity;
    BigDecimal price;

    protected OrderItem() {
    }

    OrderItem(ShopOrder order, String sku, int quantity, BigDecimal price) {
        this.order = order;
        this.sku = sku;
        this.quantity = quantity;
        this.price = price;
    }
}
