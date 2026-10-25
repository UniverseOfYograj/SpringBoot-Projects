package in.yograj.learnspringboot.railway_booking.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "trains")
public class Train {

    // Train ek physical railway unit hai. Yahan seat inventory aur coach architecture base hota hai.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "train_id")
    private Long trainId;

    @Column(name = "train_number", nullable = false, unique = true, length = 30)
    private String trainNumber;

    @Column(name = "train_name", nullable = false, length = 120)
    private String trainName;

    @Column(name = "status", nullable = false, length = 30)
    private String status = "ACTIVE";

    public Train() {
    }

    public Long getTrainId() {
        return trainId;
    }

    public void setTrainId(Long trainId) {
        this.trainId = trainId;
    }

    public String getTrainNumber() {
        return trainNumber;
    }

    public void setTrainNumber(String trainNumber) {
        this.trainNumber = trainNumber;
    }

    public String getTrainName() {
        return trainName;
    }

    public void setTrainName(String trainName) {
        this.trainName = trainName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
