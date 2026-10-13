package com.aethercore.backend.pagination;

import com.aethercore.backend.fetch.FetchBuyer;
import com.aethercore.backend.fetch.FetchOrder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Two-step pagination reader for collection-bearing entities (M2-E4B).
 *
 * Step 1: Queries paginated IDs using database-level limit/offset.
 * Step 2: Fetches collections eagerly via IN predicate for the selected page IDs.
 * Reorders in-memory based strictly on Step 1 ID ordering to guarantee deterministic page contract.
 */
public class BuyerPageReader {

    public record TwoStepResult(List<Long> phase1Ids, List<BuyerOrdersView> views) {
    }

    public static TwoStepResult readPage(EntityManager em, int offset, int pageSize) {
        // Step 1: Query IDs with pagination
        TypedQuery<Long> idQuery = em.createQuery(
                "select b.id from FetchBuyer b order by b.name asc, b.id asc",
                Long.class
        );
        idQuery.setFirstResult(offset);
        idQuery.setMaxResults(pageSize);
        List<Long> ids = idQuery.getResultList();

        if (ids.isEmpty()) {
            return new TwoStepResult(Collections.emptyList(), Collections.emptyList());
        }

        // Step 2: Fetch collection eagerly for the selected IDs
        TypedQuery<FetchBuyer> fetchQuery = em.createQuery(
                "select b from FetchBuyer b left join fetch b.orders where b.id in :ids",
                FetchBuyer.class
        );
        fetchQuery.setParameter("ids", ids);
        List<FetchBuyer> fetchedBuyers = fetchQuery.getResultList();

        // Index fetched buyers by ID
        Map<Long, FetchBuyer> buyerMap = new HashMap<>();
        for (FetchBuyer b : fetchedBuyers) {
            buyerMap.put(b.getId(), b);
        }

        // Reconstruct views strictly following Step 1 ID ordering
        List<BuyerOrdersView> views = new ArrayList<>();
        for (Long id : ids) {
            FetchBuyer b = buyerMap.get(id);
            if (b != null) {
                List<BuyerOrdersView.OrderLine> orderLines = new ArrayList<>();
                if (b.getOrders() != null) {
                    for (FetchOrder o : b.getOrders()) {
                        orderLines.add(new BuyerOrdersView.OrderLine(o.getId(), o.getQuantity()));
                    }
                    orderLines.sort(Comparator.comparing(BuyerOrdersView.OrderLine::orderId));
                }
                views.add(new BuyerOrdersView(b.getId(), b.getName(), orderLines));
            }
        }

        return new TwoStepResult(ids, views);
    }
}
