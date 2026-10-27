package in.yograj.learnspringboot.railway_booking.entity;

import in.yograj.learnspringboot.railway_booking.dto.BookingMode;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "seat_allocations",
        indexes = {
                @Index(name = "idx_allocation_inventory_status", columnList = "inventory_id, status"),
                @Index(name = "idx_allocation_hold_expiry", columnList = "hold_expires_at")
        })
public class SeatAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "seat_allocation_id")
    private Long seatAllocationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inventory_id", nullable = false)
    private JourneySeatInventory inventory;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "boarding_journey_stop_id", nullable = false)
    private JourneyStop boardingStop;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "alighting_journey_stop_id", nullable = false)
    private JourneyStop alightingStop;

    @Column(name = "from_stop_seq", nullable = false)
    private Integer fromStopSeq;

    @Column(name = "to_stop_seq", nullable = false)
    private Integer toStopSeq;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hold_user_id", nullable = false)
    private User holdOwner;

    @Column(name = "status", nullable = false, length = 30)
    private String status = "HOLD";

    @Enumerated(EnumType.STRING)
    @Column(name = "booking_mode", nullable = false, length = 20)
    private BookingMode bookingMode = BookingMode.GENERAL;

    @Column(name = "admission_entry_id", length = 36)
    private String admissionEntryId;

    @Column(name = "hold_expires_at")
    private LocalDateTime holdExpiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getSeatAllocationId() {
        return seatAllocationId;
    }

    public void setSeatAllocationId(Long seatAllocationId) {
        this.seatAllocationId = seatAllocationId;
    }

    public JourneySeatInventory getInventory() {
        return inventory;
    }

    public void setInventory(JourneySeatInventory inventory) {
        this.inventory = inventory;
    }

    public JourneyStop getBoardingStop() {
        return boardingStop;
    }

    public void setBoardingStop(JourneyStop boardingStop) {
        this.boardingStop = boardingStop;
    }

    public JourneyStop getAlightingStop() {
        return alightingStop;
    }

    public void setAlightingStop(JourneyStop alightingStop) {
        this.alightingStop = alightingStop;
    }

    public Integer getFromStopSeq() {
        return fromStopSeq;
    }

    public void setFromStopSeq(Integer fromStopSeq) {
        this.fromStopSeq = fromStopSeq;
    }

    public Integer getToStopSeq() {
        return toStopSeq;
    }

    public void setToStopSeq(Integer toStopSeq) {
        this.toStopSeq = toStopSeq;
    }

    public User getHoldOwner() {
        return holdOwner;
    }

    public void setHoldOwner(User holdOwner) {
        this.holdOwner = holdOwner;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public BookingMode getBookingMode() {
        return bookingMode;
    }

    public void setBookingMode(BookingMode bookingMode) {
        this.bookingMode = bookingMode;
    }

    public String getAdmissionEntryId() {
        return admissionEntryId;
    }

    public void setAdmissionEntryId(String admissionEntryId) {
        this.admissionEntryId = admissionEntryId;
    }

    public LocalDateTime getHoldExpiresAt() {
        return holdExpiresAt;
    }

    public void setHoldExpiresAt(LocalDateTime holdExpiresAt) {
        this.holdExpiresAt = holdExpiresAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
