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

    @Column(name = "operating_days", length = 60)
    private String operatingDays = "DAILY";

    @Column(name = "class_profile", length = 30)
    private String classProfile = "EXPRESS";

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

    public String getOperatingDays() {
        return operatingDays;
    }

    public void setOperatingDays(String operatingDays) {
        this.operatingDays = operatingDays;
    }

    public String getClassProfile() {
        return classProfile;
    }

    public void setClassProfile(String classProfile) {
        this.classProfile = classProfile;
    }

    public boolean operatesOn(java.time.DayOfWeek dayOfWeek) {
        if (dayOfWeek == null) {
            return false;
        }
        if (this.operatingDays == null || this.operatingDays.isBlank()
                || "DAILY".equalsIgnoreCase(this.operatingDays.trim())) {
            return true;
        }
        String daysUpper = this.operatingDays.toUpperCase();
        String fullDay = dayOfWeek.name();
        String abbrev = fullDay.substring(0, 3);
        String isoNum = String.valueOf(dayOfWeek.getValue());

        String[] tokens = daysUpper.split("[,\\s/|]+");
        for (String token : tokens) {
            String trimmed = token.trim();
            if (trimmed.equalsIgnoreCase("DAILY")
                    || trimmed.equalsIgnoreCase(fullDay)
                    || trimmed.equalsIgnoreCase(abbrev)
                    || trimmed.equals(isoNum)) {
                return true;
            }
        }
        return false;
    }
}
