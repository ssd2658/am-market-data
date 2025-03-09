package com.am.marketdata.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.am.common.amcommondata.service.AssetService;
import com.am.common.investment.model.equity.EquityPrice;
import com.am.common.investment.service.EquityService;
import com.am.marketdata.kafka.producer.KafkaProducerService;
import com.am.marketdata.upstock.adapter.UpStockAdapter;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockDataSchedulerService {
    private final UpStockAdapter upStockAdapter;
    private final AssetService assetService;
    private final EquityService equityService;
    private final KafkaProducerService kafkaProducerService;

    private static final int BATCH_SIZE = 50;
    private static final String NSE_PREFIX = "NSE_EQ|";

    //@Scheduled(fixedRate = 10000)
    @Transactional
    public void fetchAndPersistStockData() {
        log.info("=== Starting scheduled stock data fetch and persist job ===");
        try {
            // Get all ISINs from database
            List<String> isins = getDistinctIsin();
            if (isins.isEmpty()) {
                log.warn("No stocks found in portfolio");
                return;
            }

            // Format ISINs with NSE prefix
            Set<String> formattedIsins = isins.stream()
                .map(isin -> NSE_PREFIX + isin)
                .collect(Collectors.toSet());

            // Process in batches
            List<List<String>> batches = partition(formattedIsins.stream().toList(), BATCH_SIZE);
            log.info("Processing {} stocks in {} batches", isins.size(), batches.size());

            List<EquityPrice> allUpdatedStocks = new ArrayList<>();
            boolean hasErrors = false;

            for (List<String> batch : batches) {
                try {
                    var equityPrices = upStockAdapter.getStocksOHLC(batch);
                    if (!equityPrices.isEmpty()) {
                        equityService.saveAllPrices(equityPrices);
                        allUpdatedStocks.addAll(equityPrices);
                    } else {
                        log.warn("Received empty response for batch. Skipping Kafka event.");
                        hasErrors = true;
                    }
                } catch (Exception e) {
                    log.error("Error processing batch: {}", e.getMessage(), e);
                    hasErrors = true;
                }
            }

            // Only send Kafka events if we have data and no errors occurred
            if (!allUpdatedStocks.isEmpty() && !hasErrors) {
                log.info("Sending Kafka events for {} updated stocks", allUpdatedStocks.size());
                kafkaProducerService.sendEquityPriceUpdates(allUpdatedStocks);
            } else {
                log.warn("Skipping Kafka events due to errors or no data");
            }

        } catch (Exception e) {
            log.error("Error in stock data scheduler: {}", e.getMessage(), e);
        }
        log.info("=== Completed scheduled stock data fetch and persist job ===");
    }

    private List<String> getDistinctIsin() {
        return assetService.findDistinctIsins();
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        return IntStream.range(0, (list.size() + size - 1) / size)
            .mapToObj(i -> list.subList(i * size, Math.min(list.size(), (i + 1) * size)))
            .collect(Collectors.toList());
    }
}