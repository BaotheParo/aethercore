package com.aethercore.backend.queryplan;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Small, focused semantic parser for PostgreSQL JSON execution plans.
 * Recursively traverses the "Plan" tree using Jackson JsonNode without assuming
 * node locations, extracting costs, actual execution metrics, filters, and buffer counts.
 */
public final class ExplainPlanReader {

    private ExplainPlanReader() {}

    public record PlanNodeSummary(
            String path,
            String nodeType,
            String relationName,
            String indexName,
            Double startupCost,
            Double totalCost,
            Long planRows,
            Integer planWidth,
            Long actualRows,
            Long actualLoops,
            String indexCond,
            String filter,
            String recheckCond,
            Long rowsRemovedByFilter,
            List<String> sortKey,
            String sortMethod,
            Long sharedHitBlocks,
            Long sharedReadBlocks
    ) {}

    public record PlanReport(
            String rootNodeType,
            Double planningTimeMs,
            Double executionTimeMs,
            Double totalCost,
            Long actualRows,
            List<String> allNodeTypes,
            boolean hasSortNode,
            String scanNodeType,
            String scanIndexName,
            List<PlanNodeSummary> nodeSummaries
    ) {}

    /**
     * Parses a PostgreSQL JSON plan array or object into a structured PlanReport.
     */
    public static PlanReport parse(JsonNode root) {
        if (root == null || root.isNull()) {
            throw new IllegalArgumentException("Root JSON node cannot be null");
        }

        JsonNode container = root;
        if (root.isArray() && !root.isEmpty()) {
            container = root.get(0);
        }

        Double planningTime = getDouble(container, "Planning Time");
        Double executionTime = getDouble(container, "Execution Time");

        JsonNode planNode = container.get("Plan");
        if (planNode == null || planNode.isNull()) {
            throw new IllegalArgumentException("Expected 'Plan' node in EXPLAIN JSON output");
        }

        List<PlanNodeSummary> summaries = new ArrayList<>();
        List<String> allNodeTypes = new ArrayList<>();

        traverse(planNode, "Plan", summaries, allNodeTypes);

        String rootNodeType = planNode.has("Node Type") ? planNode.get("Node Type").asText() : "Unknown";
        Double totalCost = getDouble(planNode, "Total Cost");
        Long actualRows = getLong(planNode, "Actual Rows");

        boolean hasSort = false;
        String scanNodeType = null;
        String scanIndexName = null;

        for (PlanNodeSummary s : summaries) {
            if ("Sort".equalsIgnoreCase(s.nodeType()) || s.nodeType().toLowerCase().contains("sort")) {
                hasSort = true;
            }
            if (s.nodeType().toLowerCase().contains("scan")) {
                if (scanNodeType == null) {
                    scanNodeType = s.nodeType();
                    scanIndexName = s.indexName();
                }
            }
        }

        return new PlanReport(
                rootNodeType,
                planningTime,
                executionTime,
                totalCost,
                actualRows,
                Collections.unmodifiableList(allNodeTypes),
                hasSort,
                scanNodeType,
                scanIndexName,
                Collections.unmodifiableList(summaries)
        );
    }

    private static void traverse(JsonNode node, String currentPath, List<PlanNodeSummary> accumulator, List<String> allNodeTypes) {
        String nodeType = node.has("Node Type") ? node.get("Node Type").asText() : "Unknown";
        allNodeTypes.add(nodeType);

        String relationName = getString(node, "Relation Name");
        String indexName = getString(node, "Index Name");
        Double startupCost = getDouble(node, "Startup Cost");
        Double totalCost = getDouble(node, "Total Cost");
        Long planRows = getLong(node, "Plan Rows");
        Integer planWidth = getInt(node, "Plan Width");
        Long actualRows = getLong(node, "Actual Rows");
        Long actualLoops = getLong(node, "Actual Loops");
        String indexCond = getString(node, "Index Cond");
        String filter = getString(node, "Filter");
        String recheckCond = getString(node, "Recheck Cond");
        Long rowsRemovedByFilter = getLong(node, "Rows Removed by Filter");
        List<String> sortKey = getList(node, "Sort Key");
        String sortMethod = getString(node, "Sort Method");
        Long sharedHitBlocks = getLong(node, "Shared Hit Blocks");
        Long sharedReadBlocks = getLong(node, "Shared Read Blocks");

        accumulator.add(new PlanNodeSummary(
                currentPath,
                nodeType,
                relationName,
                indexName,
                startupCost,
                totalCost,
                planRows,
                planWidth,
                actualRows,
                actualLoops,
                indexCond,
                filter,
                recheckCond,
                rowsRemovedByFilter,
                sortKey,
                sortMethod,
                sharedHitBlocks,
                sharedReadBlocks
        ));

        JsonNode plans = node.get("Plans");
        if (plans != null && plans.isArray()) {
            for (int i = 0; i < plans.size(); i++) {
                JsonNode child = plans.get(i);
                String childType = child.has("Node Type") ? child.get("Node Type").asText() : "Node";
                String childPath = currentPath + " -> " + childType + "[" + i + "]";
                traverse(child, childPath, accumulator, allNodeTypes);
            }
        }
    }

    private static String getString(JsonNode node, String field) {
        return (node.has(field) && !node.get(field).isNull()) ? node.get(field).asText() : null;
    }

    private static Double getDouble(JsonNode node, String field) {
        return (node.has(field) && !node.get(field).isNull()) ? node.get(field).asDouble() : null;
    }

    private static Long getLong(JsonNode node, String field) {
        return (node.has(field) && !node.get(field).isNull()) ? node.get(field).asLong() : null;
    }

    private static Integer getInt(JsonNode node, String field) {
        return (node.has(field) && !node.get(field).isNull()) ? node.get(field).asInt() : null;
    }

    private static List<String> getList(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) {
            return null;
        }
        JsonNode arr = node.get(field);
        if (arr.isArray()) {
            List<String> list = new ArrayList<>();
            for (JsonNode item : arr) {
                list.add(item.asText());
            }
            return list;
        }
        return List.of(arr.asText());
    }
}
