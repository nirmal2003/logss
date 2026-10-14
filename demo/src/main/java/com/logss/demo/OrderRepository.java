package com.logss.demo;

import org.springframework.data.jpa.repository.JpaRepository;

interface OrderRepository extends JpaRepository<ShopOrder, Long> {
}
