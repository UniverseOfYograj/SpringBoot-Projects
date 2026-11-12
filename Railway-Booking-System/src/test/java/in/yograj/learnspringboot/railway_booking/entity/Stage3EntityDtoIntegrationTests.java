package in.yograj.learnspringboot.railway_booking.entity;

import in.yograj.learnspringboot.railway_booking.dto.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Stage3EntityDtoIntegrationTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Booking entity supports both canonical PNR and dev multi-passenger, fare breakdown and distance")
    void bookingEntityPreservesPnrAndSupportsMultiPassenger() {
        Booking booking = new Booking();
        booking.setBookingRef("BR-12345678");
        booking.setPnr("1234567890");
        booking.setAmount(new BigDecimal("950.00"));
        booking.setDistanceKm(1447);
        booking.setFareBreakdownJson("{\"baseFare\":800.00,\"tax\":150.00}");

        Passenger p1 = new Passenger();
        p1.setFullName("Aarav Sharma");
        p1.setAge(28);
        p1.setGender("MALE");
        p1.setBerthPreference("LOWER");

        Passenger p2 = new Passenger();
        p2.setFullName("Diya Sharma");
        p2.setAge(26);
        p2.setGender("FEMALE");
        p2.setBerthPreference("UPPER");

        booking.addPassenger(p1);
        booking.addPassenger(p2);

        assertThat(booking.getPnr()).isEqualTo("1234567890");
        assertThat(booking.getBookingRef()).isEqualTo("BR-12345678");
        assertThat(booking.getAmount()).isEqualByComparingTo("950.00");
        assertThat(booking.getDistanceKm()).isEqualTo(1447);
        assertThat(booking.getFareBreakdownJson()).contains("tax");
        assertThat(booking.getPassengers()).hasSize(2);
        assertThat(p1.getBooking()).isSameAs(booking);
        assertThat(p2.getBooking()).isSameAs(booking);
    }

    @Test
    @DisplayName("SeatAllocation preserves Tatkal bookingMode and admissionEntryId")
    void seatAllocationPreservesTatkalFields() {
        SeatAllocation allocation = new SeatAllocation();
        allocation.setBookingMode(BookingMode.TATKAL);
        allocation.setAdmissionEntryId("admit-entry-uuid-1234");
        allocation.setStatus("HOLD");

        assertThat(allocation.getBookingMode()).isEqualTo(BookingMode.TATKAL);
        assertThat(allocation.getAdmissionEntryId()).isEqualTo("admit-entry-uuid-1234");
        assertThat(allocation.getStatus()).isEqualTo("HOLD");
    }

    @Test
    @DisplayName("PaymentTransaction preserves Razorpay gateway columns")
    void paymentTransactionPreservesRazorpayFields() {
        PaymentTransaction txn = new PaymentTransaction();
        txn.setBookingRef("BR-TEST");
        txn.setTransactionRef("TXN-123");
        txn.setPaymentMethod("RAZORPAY");
        txn.setAmount(new BigDecimal("1200.00"));
        txn.setGatewayOrderId("order_rzp_123");
        txn.setGatewayPaymentId("pay_rzp_456");
        txn.setGatewayOrderReceipt("receipt_789");
        txn.setStatus("SUCCESS");

        assertThat(txn.getGatewayOrderId()).isEqualTo("order_rzp_123");
        assertThat(txn.getGatewayPaymentId()).isEqualTo("pay_rzp_456");
        assertThat(txn.getGatewayOrderReceipt()).isEqualTo("receipt_789");
        assertThat(txn.getAmount()).isEqualByComparingTo("1200.00");
    }

    @Test
    @DisplayName("TrainStop and JourneyStop support distanceFromOriginKm from V9 migration")
    void trainAndJourneyStopsSupportDistanceMetric() {
        TrainStop trainStop = new TrainStop();
        trainStop.setDistanceFromOriginKm(new BigDecimal("1447.50"));
        assertThat(trainStop.getDistanceFromOriginKm()).isEqualByComparingTo("1447.50");

        JourneyStop journeyStop = new JourneyStop();
        journeyStop.setDistanceFromOriginKm(new BigDecimal("218.00"));
        assertThat(journeyStop.getDistanceFromOriginKm()).isEqualByComparingTo("218.00");
    }

    @Test
    @DisplayName("CreateBookingRequestDTO backward compatibility with all overloaded constructors")
    void createBookingRequestDtoConstructorsCompatibility() {
        // 4-param constructor (Phase 17.16)
        CreateBookingRequestDTO dto4 = new CreateBookingRequestDTO(1L, 10L, 2L, 3L);
        assertThat(dto4.bookingMode()).isEqualTo(BookingMode.GENERAL);
        assertThat(dto4.seatId()).isEqualTo(10L);
        assertThat(dto4.passengers()).isNull();

        // 5-param constructor (Tatkal)
        CreateBookingRequestDTO dto5 = new CreateBookingRequestDTO(1L, 10L, 2L, 3L, BookingMode.TATKAL);
        assertThat(dto5.bookingMode()).isEqualTo(BookingMode.TATKAL);

        // 6-param constructor (Phase 17.17 class-aware)
        CreateBookingRequestDTO dto6 = new CreateBookingRequestDTO(1L, 10L, 2L, 3L, "3A", "B1");
        assertThat(dto6.travelClass()).isEqualTo("3A");
        assertThat(dto6.coachNumber()).isEqualTo("B1");
        assertThat(dto6.bookingMode()).isEqualTo(BookingMode.GENERAL);

        // 7-param constructor (Phase 17.18 multi-passenger)
        PassengerRequestDTO pReq = new PassengerRequestDTO("Rohan", 32, "MALE", 15L, "LOWER");
        CreateBookingRequestDTO dto7 = new CreateBookingRequestDTO(1L, null, 2L, 3L, "3A", "B1", List.of(pReq));
        assertThat(dto7.passengers()).hasSize(1);
        assertThat(dto7.seatId()).isNull();

        // Validation rule: cannot omit both seatId and passengers
        assertThrows(IllegalArgumentException.class, () ->
                new CreateBookingRequestDTO(1L, null, 2L, 3L, BookingMode.GENERAL, "3A", "B1", null));
    }

    @Test
    @DisplayName("BookingResponseDTO backward compatibility with canonical PNR and dev fields")
    void bookingResponseDtoCompatibility() {
        LocalDateTime now = LocalDateTime.now();
        // 10-param constructor
        BookingResponseDTO dto10 = new BookingResponseDTO(1L, "REF1", 2L, 3L, "S1", 12, "CONFIRMED", "SUCCESS", new BigDecimal("500.00"), now);
        assertThat(dto10.pnr()).isNull();
        assertThat(dto10.passengers()).isEmpty();

        // 11-param constructor (Canonical with PNR)
        BookingResponseDTO dto11 = new BookingResponseDTO(1L, "REF1", "9876543210", 2L, 3L, "S1", 12, "CONFIRMED", "SUCCESS", new BigDecimal("500.00"), now);
        assertThat(dto11.pnr()).isEqualTo("9876543210");
        assertThat(dto11.passengers()).isEmpty();

        // Full 14-component constructor
        PassengerResponseDTO pResp = new PassengerResponseDTO(100L, "Amit", 40, "MALE", "LOWER", 3L, 12, "BERTH", 5L, "S1", "SL", 20L);
        FareComponentDTO fare = new FareComponentDTO(new BigDecimal("400.00"), new BigDecimal("20.00"), new BigDecimal("10.00"), BigDecimal.ZERO, new BigDecimal("430.00"));
        BookingResponseDTO dtoFull = new BookingResponseDTO(1L, "REF1", "9876543210", 2L, 3L, "S1", 12, "CONFIRMED", "SUCCESS", new BigDecimal("430.00"), now, List.of(pResp), fare, 800);

        assertThat(dtoFull.pnr()).isEqualTo("9876543210");
        assertThat(dtoFull.passengers()).hasSize(1);
        assertThat(dtoFull.fareBreakdown().total()).isEqualByComparingTo("430.00");
        assertThat(dtoFull.distanceKm()).isEqualTo(800);
    }

    @Test
    @DisplayName("BookingDetailResponseDTO backward compatibility with PNR and passenger list")
    void bookingDetailResponseDtoCompatibility() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = LocalDate.now();

        // 26-param constructor (Canonical with PNR)
        BookingDetailResponseDTO detail26 = new BookingDetailResponseDTO(
                1L, "REF1", "1234567890", 10L, "traveler", 2L, "12301", "Rajdhani",
                "HWH", "Howrah", "NDLS", "New Delhi", today, LocalTime.of(16, 50), LocalTime.of(10, 0),
                5L, "B1", "3A", 12L, 5, "BERTH", 99L, "CONFIRMED", "SUCCESS",
                new BigDecimal("1500.00"), now
        );
        assertThat(detail26.pnr()).isEqualTo("1234567890");
        assertThat(detail26.passengers()).isEmpty();
        assertThat(detail26.fareBreakdown()).isNull();

        // Full 29-component record
        PassengerResponseDTO pResp = new PassengerResponseDTO(101L, "Sneha", 25, "FEMALE", "UPPER", 12L, 5, "BERTH", 5L, "B1", "3A", 99L);
        FareComponentDTO fare = new FareComponentDTO(new BigDecimal("1300.00"), new BigDecimal("40.00"), new BigDecimal("30.00"), new BigDecimal("70.00"), new BigDecimal("1440.00"));
        BookingDetailResponseDTO detailFull = new BookingDetailResponseDTO(
                1L, "REF1", "1234567890", 10L, "traveler", 2L, "12301", "Rajdhani",
                "HWH", "Howrah", "NDLS", "New Delhi", today, LocalTime.of(16, 50), LocalTime.of(10, 0),
                5L, "B1", "3A", 12L, 5, "BERTH", 99L, "CONFIRMED", "SUCCESS",
                new BigDecimal("1440.00"), now, List.of(pResp), fare, 1447
        );
        assertThat(detailFull.pnr()).isEqualTo("1234567890");
        assertThat(detailFull.passengers()).hasSize(1);
        assertThat(detailFull.distanceKm()).isEqualTo(1447);
        assertThat(detailFull.fareBreakdown().total()).isEqualByComparingTo("1440.00");
    }

    @Test
    @DisplayName("SeatAvailabilityResponseDTO backward compatibility with coachType")
    void seatAvailabilityResponseDtoCompatibility() {
        LocalDateTime now = LocalDateTime.now();
        // 13-param constructor
        SeatAvailabilityResponseDTO dto13 = new SeatAvailabilityResponseDTO(
                1L, 2L, 3L, "B1", 10L, 5, "BERTH", "AVAILABLE", null, now, null, 100L, 101L
        );
        assertThat(dto13.coachType()).isNull();
        assertThat(dto13.coachNumber()).isEqualTo("B1");

        // 14-param constructor
        SeatAvailabilityResponseDTO dto14 = new SeatAvailabilityResponseDTO(
                1L, 2L, 3L, "B1", "3A", 10L, 5, "BERTH", "AVAILABLE", null, now, null, 100L, 101L
        );
        assertThat(dto14.coachType()).isEqualTo("3A");
    }

    @Test
    @DisplayName("New DTOs JSON serialization and deserialization")
    void newDtosJsonRoundtrip() throws Exception {
        FareComponentDTO fare = new FareComponentDTO(
                new BigDecimal("800.00"), new BigDecimal("40.00"), new BigDecimal("30.00"),
                new BigDecimal("43.50"), new BigDecimal("913.50")
        );
        String fareJson = objectMapper.writeValueAsString(fare);
        FareComponentDTO fareDeser = objectMapper.readValue(fareJson, FareComponentDTO.class);
        assertThat(fareDeser.total()).isEqualByComparingTo("913.50");

        FareQuoteResponseDTO quote = new FareQuoteResponseDTO(
                1L, "3A", "AC 3 Tier", 1447, "EXACT_ROUTE", 2, fare, new BigDecimal("1827.00"), "INR", new BigDecimal("1.10")
        );
        String quoteJson = objectMapper.writeValueAsString(quote);
        FareQuoteResponseDTO quoteDeser = objectMapper.readValue(quoteJson, FareQuoteResponseDTO.class);
        assertThat(quoteDeser.bookingTotal()).isEqualByComparingTo("1827.00");
        assertThat(quoteDeser.distanceKm()).isEqualTo(1447);

        BatchHoldSeatRequestDTO batch = new BatchHoldSeatRequestDTO(
                List.of(101L, 102L, 103L), 10, 1L, 5L, "3A", "B1"
        );
        String batchJson = objectMapper.writeValueAsString(batch);
        BatchHoldSeatRequestDTO batchDeser = objectMapper.readValue(batchJson, BatchHoldSeatRequestDTO.class);
        assertThat(batchDeser.seatIds()).containsExactly(101L, 102L, 103L);
        assertThat(batchDeser.travelClass()).isEqualTo("3A");
    }
}
