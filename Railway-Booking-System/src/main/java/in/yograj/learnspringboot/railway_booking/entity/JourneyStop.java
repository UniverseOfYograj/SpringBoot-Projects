package in.yograj.learnspringboot.railway_booking.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "journey_stops",
        uniqueConstraints = @UniqueConstraint(name = "uk_journey_stop_sequence",
                columnNames = {"journey_id", "stop_sequence"}),
        indexes = @Index(name = "idx_journey_stop_station", columnList = "station_id, journey_id"))
public class JourneyStop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "journey_stop_id")
    private Long journeyStopId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journey_id", nullable = false)
    private Journey journey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "train_stop_id")
    private TrainStop trainStop;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "station_id", nullable = false)
    private Station station;

    @Column(name = "stop_sequence", nullable = false)
    private Integer stopSequence;

    @Column(name = "arrival_at")
    private LocalDateTime arrivalAt;

    @Column(name = "departure_at")
    private LocalDateTime departureAt;

    public Long getJourneyStopId() {
        return journeyStopId;
    }

    public void setJourneyStopId(Long journeyStopId) {
        this.journeyStopId = journeyStopId;
    }

    public Journey getJourney() {
        return journey;
    }

    public void setJourney(Journey journey) {
        this.journey = journey;
    }

    public TrainStop getTrainStop() {
        return trainStop;
    }

    public void setTrainStop(TrainStop trainStop) {
        this.trainStop = trainStop;
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

    public LocalDateTime getArrivalAt() {
        return arrivalAt;
    }

    public void setArrivalAt(LocalDateTime arrivalAt) {
        this.arrivalAt = arrivalAt;
    }

    public LocalDateTime getDepartureAt() {
        return departureAt;
    }

    public void setDepartureAt(LocalDateTime departureAt) {
        this.departureAt = departureAt;
    }
}
