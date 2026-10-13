package com.aethercore.backend.pagination;

import java.util.List;

/**
 * Clean DTO view representing a buyer and their associated order lines.
 * Contains purely detached values, no Hibernate managed entities or proxies.
 */
public record BuyerOrdersView(Long buyerId, String buyerName, List<OrderLine> orders) {

    public record OrderLine(Long orderId, int quantity) {
    }
}
