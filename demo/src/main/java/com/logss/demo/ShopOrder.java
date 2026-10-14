package com.logss.demo;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
class ShopOrder {
    @Id
    @GeneratedValue
    Long id;

    // Lazy, so reading it per order in a loop runs one query per order (N+1).
    @ManyToOne(fetch = FetchType.LAZY)
    Customer customer;

    @OneToMany(mappedBy = "order", fetch = FetchType.LAZY)
    List<OrderItem> items = new ArrayList<>();

    protected ShopOrder() {
    }

    ShopOrder(Customer customer) {
        this.customer = customer;
    }
}
