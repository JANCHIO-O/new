package com.example.library.statistics.service;

import com.example.library.common.entity.BorrowRecord;
import com.example.library.common.entity.ReaderInfo;
import com.example.library.common.repository.BorrowRecordRepository;
import com.example.library.common.repository.CirculationBookRepository;
import com.example.library.common.repository.ReaderInfoRepository;
import com.example.library.statistics.entity.StatisticsRecord;
import com.example.library.statistics.repository.StatisticsRecordRepository;
import com.example.library.statistics.view.ReaderBorrowSummary;
import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        StatisticsPeriodRange range = resolvePeriodRange(statPeriod, LocalDate.now());
        List<BorrowRecord> extractedRecords = extractRecords(range.getStartDate(), range.getEndDate());
        List<BorrowRecord> borrowEvents = filterBorrowEvents(extractedRecords);
        boolean noData = borrowEvents.isEmpty();

        long totalBorrow = 0;
        long activeReaderCount = 0;
        if ("流通统计".equals(statType)) {
            totalBorrow = borrowEvents.size();
        } else if ("读者统计".equals(statType)) {
            Map<String, Long> borrowCountByStudent = buildBorrowCountByStudent(borrowEvents);
            activeReaderCount = fetchStudents().stream()
                    .filter(reader -> borrowCountByStudent.getOrDefault(reader.getCardNo(), 0L) > 0)
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

    public List<BorrowRecord> fetchBorrowDetails(StatisticsRecord record) {
        StatisticsPeriodRange range = resolvePeriodRange(record.getStatPeriod(), resolveBaseDate(record));
        List<BorrowRecord> extractedRecords = extractRecords(range.getStartDate(), range.getEndDate());
        return filterBorrowEvents(extractedRecords);
    }

    public List<ReaderBorrowSummary> buildReaderSummaries(StatisticsRecord record) {
        List<BorrowRecord> borrowEvents = fetchBorrowDetails(record);
        Map<String, List<BorrowRecord>> recordsByStudent = borrowEvents.stream()
                .collect(Collectors.groupingBy(BorrowRecord::getCardNo));
        return fetchStudents().stream()
                .sorted(Comparator.comparing(ReaderInfo::getCardNo))
                .map(student -> {
                    List<BorrowRecord> records = recordsByStudent.getOrDefault(student.getCardNo(), List.of());
                    String borrowedTitlesText = records.stream()
                            .map(BorrowRecord::getTitle)
                            .filter(title -> title != null && !title.isBlank())
                            .distinct()
                            .sorted()
                            .collect(Collectors.joining("、"));
                    if (borrowedTitlesText.isBlank()) {
                        borrowedTitlesText = "无";
                    }
                    return new ReaderBorrowSummary(
                            student.getCardNo(),
                            student.getName(),
                            records.size(),
                            borrowedTitlesText
                    );
                })
                .collect(Collectors.toList());
    }

    private LocalDate resolveBaseDate(StatisticsRecord record) {
        if (record.getStatDate() != null) {
            return record.getStatDate().toLocalDate();
        }
        return LocalDate.now();
    }

    private StatisticsPeriodRange resolvePeriodRange(String statPeriod, LocalDate baseDate) {
        LocalDate startDate;
        switch (statPeriod) {
            case "日":
                startDate = baseDate;
                break;
            case "周":
                startDate = baseDate.with(DayOfWeek.MONDAY);
                break;
            case "月":
                startDate = baseDate.withDayOfMonth(1);
                break;
            case "年":
                startDate = baseDate.withDayOfYear(1);
                break;
            default:
                throw new IllegalArgumentException("Unsupported stat period: " + statPeriod);
        }
        return new StatisticsPeriodRange(Date.valueOf(startDate), Date.valueOf(baseDate));
    }

    private List<BorrowRecord> extractRecords(Date startDate, Date endDate) {
        List<BorrowRecord> records = borrowRecordRepository.findByFlowDateBetween(startDate, endDate);
        return records.stream()
                .filter(record -> readerInfoRepository.existsById(record.getCardNo()))
                .filter(record -> circulationBookRepository.findByBookId(record.getBookId()).isPresent())
                .collect(Collectors.toList());
    }

    private List<BorrowRecord> filterBorrowEvents(List<BorrowRecord> records) {
        return records.stream()
                .filter(record -> "借阅".equals(record.getEventType()))
                .collect(Collectors.toList());
    }

    private Map<String, Long> buildBorrowCountByStudent(List<BorrowRecord> borrowEvents) {
        return borrowEvents.stream()
                .filter(record -> isStudent(record.getCardNo()))
                .collect(Collectors.groupingBy(BorrowRecord::getCardNo, Collectors.counting()));
    }

    private List<ReaderInfo> fetchStudents() {
        return readerInfoRepository.findAll().stream()
                .filter(reader -> "STUDENT".equals(reader.getRole()))
                .collect(Collectors.toList());
    }

    private boolean isStudent(String cardNo) {
        return readerInfoRepository.findById(cardNo)
                .map(reader -> "STUDENT".equals(reader.getRole()))
                .orElse(false);
    }

    private String generateStatId() {
        return "STAT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
