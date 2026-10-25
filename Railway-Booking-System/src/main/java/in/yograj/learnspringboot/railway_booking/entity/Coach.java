package in.yograj.learnspringboot.railway_booking.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "coaches", uniqueConstraints = {
        @UniqueConstraint(name = "uk_train_coach_number", columnNames = {"train_id", "coach_number"})
})
public class Coach {

    // Coach train ke andar seat inventory ka logical bucket hai. Yahan seat availability and allocation logic hoti hai.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "coach_id")
    private Long coachId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "train_id", nullable = false)
    private Train train;

    @Column(name = "coach_number", nullable = false, length = 20)
    private String coachNumber;

    @Column(name = "coach_type", nullable = false, length = 30)
    private String coachType;

    @Column(name = "seat_capacity", nullable = false)
    private Integer seatCapacity;

    @Column(name = "status", nullable = false, length = 30)
    private String status = "ACTIVE";

    public Coach() {
    }

    public Long getCoachId() {
        return coachId;
    }

    public void setCoachId(Long coachId) {
        this.coachId = coachId;
    }

    public Train getTrain() {
        return train;
    }

    public void setTrain(Train train) {
        this.train = train;
    }

    public String getCoachNumber() {
        return coachNumber;
    }

    public void setCoachNumber(String coachNumber) {
        this.coachNumber = coachNumber;
    }

    public String getCoachType() {
        return coachType;
    }

    public void setCoachType(String coachType) {
        this.coachType = coachType;
    }

    public Integer getSeatCapacity() {
        return seatCapacity;
    }

    public void setSeatCapacity(Integer seatCapacity) {
        this.seatCapacity = seatCapacity;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
