package in.yograj.learnspringboot.railway_booking.entity;

import jakarta.persistence.*;

import java.time.LocalTime;

@Entity
@Table(name = "train_stops",
        uniqueConstraints = @UniqueConstraint(name = "uk_route_stop_sequence",
                columnNames = {"train_route_id", "stop_sequence"}),
        indexes = @Index(name = "idx_train_stop_station", columnList = "station_id"))
public class TrainStop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "train_stop_id")
    private Long trainStopId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "train_route_id", nullable = false)
    private TrainRoute trainRoute;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "station_id", nullable = false)
    private Station station;

    @Column(name = "stop_sequence", nullable = false)
    private Integer stopSequence;

    @Column(name = "arrival_time")
    private LocalTime arrivalTime;

    @Column(name = "departure_time")
    private LocalTime departureTime;

    @Column(name = "day_offset", nullable = false)
    private Integer dayOffset;

    public Long getTrainStopId() {
        return trainStopId;
    }

    public void setTrainStopId(Long trainStopId) {
        this.trainStopId = trainStopId;
    }

    public TrainRoute getTrainRoute() {
        return trainRoute;
    }

    public void setTrainRoute(TrainRoute trainRoute) {
        this.trainRoute = trainRoute;
    }

    public Station getStation() {
        return station;
    }

    public void setStation(Station station) {
        this.station = station;
    }

    public Integer getStopSequence() {
        return stopSequence;
    }

    public void setStopSequence(Integer stopSequence) {
        this.stopSequence = stopSequence;
    }

    public LocalTime getArrivalTime() {
        return arrivalTime;
    }

    public void setArrivalTime(LocalTime arrivalTime) {
        this.arrivalTime = arrivalTime;
    }

    public LocalTime getDepartureTime() {
        return departureTime;
    }

    public void setDepartureTime(LocalTime departureTime) {
        this.departureTime = departureTime;
    }

    public Integer getDayOffset() {
        return dayOffset;
    }

    public void setDayOffset(Integer dayOffset) {
        this.dayOffset = dayOffset;
    }
}
