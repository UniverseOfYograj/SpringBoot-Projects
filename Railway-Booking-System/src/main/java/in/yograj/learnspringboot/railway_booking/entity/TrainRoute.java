package in.yograj.learnspringboot.railway_booking.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "train_routes",
        uniqueConstraints = @UniqueConstraint(name = "uk_train_route_name", columnNames = {"train_id", "route_name"}))
public class TrainRoute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "train_route_id")
    private Long trainRouteId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "train_id", nullable = false)
    private Train train;

    @Column(name = "route_name", nullable = false, length = 100)
    private String routeName;

    public Long getTrainRouteId() {
        return trainRouteId;
    }

    public void setTrainRouteId(Long trainRouteId) {
        this.trainRouteId = trainRouteId;
    }

    public Train getTrain() {
        return train;
    }

    public void setTrain(Train train) {
        this.train = train;
    }

    public String getRouteName() {
        return routeName;
    }

    public void setRouteName(String routeName) {
        this.routeName = routeName;
    }
}
