package in.yograj.learnspringboot.railway_booking.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "stations")
public class Station {

    // Station entity: train route ka base point hai. Yahan se journey ka source/target decide hota hai.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "station_id")
    private Long stationId;

    @Column(name = "station_code", nullable = false, unique = true, length = 20)
    private String stationCode;

    @Column(name = "station_name", nullable = false, length = 120)
    private String stationName;

    @Column(name = "city", nullable = true, length = 120)
    private String city;

    @Column(name = "state", length = 120)
    private String state;

    public Station() {
    }

    public Long getStationId() {
        return stationId;
    }

    public void setStationId(Long stationId) {
        this.stationId = stationId;
    }

    public String getStationCode() {
        return stationCode;
    }

    public void setStationCode(String stationCode) {
        this.stationCode = stationCode;
    }

    public String getStationName() {
        return stationName;
    }

    public void setStationName(String stationName) {
        this.stationName = stationName;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }
}
