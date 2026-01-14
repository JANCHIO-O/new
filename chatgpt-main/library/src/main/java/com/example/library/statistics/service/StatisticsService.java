package com.example.library.statistics.service;

import com.example.library.common.entity.BorrowRecord;
import com.example.library.common.repository.BorrowRecordRepository;
import com.example.library.common.repository.CirculationBookRepository;
import com.example.library.common.repository.ReaderInfoRepository;
import com.example.library.statistics.entity.StatisticsRecord;
import com.example.library.statistics.repository.StatisticsRecordRepository;
import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class StatisticsService {

    private static final DateTimeFormatter REPORT_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA);

    private final BorrowRecordRepository borrowRecordRepository;
    private final ReaderInfoRepository readerInfoRepository;
    private final CirculationBookRepository circulationBookRepository;
    private final StatisticsRecordRepository statisticsRecordRepository;

    public StatisticsService(BorrowRecordRepository borrowRecordRepository,
                             ReaderInfoRepository readerInfoRepository,
                             CirculationBookRepository circulationBookRepository,
                             StatisticsRecordRepository statisticsRecordRepository) {
        this.borrowRecordRepository = borrowRecordRepository;
        this.readerInfoRepository = readerInfoRepository;
        this.circulationBookRepository = circulationBookRepository;
        this.statisticsRecordRepository = statisticsRecordRepository;
    }

    public StatisticsRecord runStatistics(String statType, String statPeriod) {
        StatisticsPeriodRange range = resolvePeriodRange(statPeriod);
        List<BorrowRecord> extractedRecords = extractRecords(range.getStartDate(), range.getEndDate());
        boolean noData = extractedRecords.isEmpty();

        long totalBorrow = 0;
        long activeReaderCount = 0;
        if ("流通统计".equals(statType)) {
            totalBorrow = extractedRecords.stream()
                    .filter(record -> "借阅".equals(record.getEventType()))
                    .count();
        } else if ("读者统计".equals(statType)) {
            activeReaderCount = extractedRecords.stream()
                    .map(BorrowRecord::getCardNo)
                    .distinct()
                    .count();
        }

        String remark = noData ? "该周期无数据" : "";
        StatisticsRecord record = new StatisticsRecord(
                generateStatId(),
                statType,
                statPeriod,
                Date.valueOf(LocalDate.now()),
                totalBorrow,
                activeReaderCount,
                remark
        );
        return statisticsRecordRepository.save(record);
    }

    public StatisticsRecord findById(String statId) {
        return statisticsRecordRepository.findById(statId).orElse(null);
    }

    public List<StatisticsRecord> findAll() {
        return statisticsRecordRepository.findAll();
    }

    public String formatDate(Date date) {
        if (date == null) {
            return "";
        }
        return date.toLocalDate().format(REPORT_DATE_FORMAT);
    }

    private StatisticsPeriodRange resolvePeriodRange(String statPeriod) {
        LocalDate today = LocalDate.now();
        LocalDate startDate;
        switch (statPeriod) {
            case "日":
                startDate = today;
                break;
            case "周":
                startDate = today.with(DayOfWeek.MONDAY);
                break;
            case "月":
                startDate = today.withDayOfMonth(1);
                break;
            case "年":
                startDate = today.withDayOfYear(1);
                break;
            default:
                throw new IllegalArgumentException("Unsupported stat period: " + statPeriod);
        }
        return new StatisticsPeriodRange(Date.valueOf(startDate), Date.valueOf(today));
    }

    private List<BorrowRecord> extractRecords(Date startDate, Date endDate) {
        List<BorrowRecord> records = borrowRecordRepository.findByFlowDateBetween(startDate, endDate);
        return records.stream()
                .filter(record -> readerInfoRepository.existsById(record.getCardNo()))
                .filter(record -> circulationBookRepository.findByBookId(record.getBookId()).isPresent())
                .collect(Collectors.toList());
    }

    private String generateStatId() {
        return "STAT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
