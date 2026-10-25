package in.yograj.learnspringboot.railway_booking.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "journeys",
        indexes = {
                @Index(name = "idx_journey_route_date", columnList = "from_station_id, to_station_id, journey_date"),
                @Index(name = "idx_journey_train_date", columnList = "train_id, journey_date")
        })
public class Journey {

    // Journey train + route + date ka logical booking window hai. Booking engine yahi se seat inventory check karta hai.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "journey_id")
    private Long journeyId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "train_id", nullable = false)
    private Train train;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "train_route_id")
    private TrainRoute trainRoute;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_station_id", nullable = false)
    private Station fromStation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_station_id", nullable = false)
    private Station toStation;

    @Column(name = "journey_date", nullable = false)
    private LocalDate journeyDate;

    @Column(name = "departure_time", nullable = false)
    private LocalTime departureTime;

    @Column(name = "arrival_time", nullable = false)
    private LocalTime arrivalTime;

    public Journey() {
    }

    public Long getJourneyId() {
        return journeyId;
    }

    public void setJourneyId(Long journeyId) {
        this.journeyId = journeyId;
    }

    public Train getTrain() {
        return train;
    }

    public void setTrain(Train train) {
        this.train = train;
    }

    public TrainRoute getTrainRoute() {
        return trainRoute;
    }

    public void setTrainRoute(TrainRoute trainRoute) {
        this.trainRoute = trainRoute;
    }

    public Station getFromStation() {
        return fromStation;
    }

    public void setFromStation(Station fromStation) {
        this.fromStation = fromStation;
    }

    public Station getToStation() {
        return toStation;
    }

    public void setToStation(Station toStation) {
        this.toStation = toStation;
    }

    public LocalDate getJourneyDate() {
        return journeyDate;
    }

    public void setJourneyDate(LocalDate journeyDate) {
        this.journeyDate = journeyDate;
    }

    public LocalTime getDepartureTime() {
        return departureTime;
    }

    public void setDepartureTime(LocalTime departureTime) {
        this.departureTime = departureTime;
    }

    public LocalTime getArrivalTime() {
        return arrivalTime;
    }

    public void setArrivalTime(LocalTime arrivalTime) {
        this.arrivalTime = arrivalTime;
    }
}
