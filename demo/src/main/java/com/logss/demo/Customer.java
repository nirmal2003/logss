package com.logss.demo;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "customer")
class Customer {
    @Id
    @GeneratedValue
    Long id;
    String name;
    String email;

    protected Customer() {
    }

    Customer(String name, String email) {
        this.name = name;
        this.email = email;
    }

    // Called on a lazy proxy, this is what triggers the query; reading the field directly would not.
    String getName() {
        return name;
    }
}
