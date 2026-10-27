package in.yograj.learnspringboot.railway_booking;

import in.yograj.learnspringboot.railway_booking.entity.Coach;
import in.yograj.learnspringboot.railway_booking.entity.Journey;
import in.yograj.learnspringboot.railway_booking.entity.JourneySeatInventory;
import in.yograj.learnspringboot.railway_booking.entity.JourneyStop;
import in.yograj.learnspringboot.railway_booking.entity.Seat;
import in.yograj.learnspringboot.railway_booking.entity.Station;
import in.yograj.learnspringboot.railway_booking.entity.Train;
import in.yograj.learnspringboot.railway_booking.entity.User;
import in.yograj.learnspringboot.railway_booking.repository.CoachRepository;
import in.yograj.learnspringboot.railway_booking.repository.JourneyRepository;
import in.yograj.learnspringboot.railway_booking.repository.JourneySeatInventoryRepository;
import in.yograj.learnspringboot.railway_booking.repository.JourneyStopRepository;
import in.yograj.learnspringboot.railway_booking.repository.SeatRepository;
import in.yograj.learnspringboot.railway_booking.repository.StationRepository;
import in.yograj.learnspringboot.railway_booking.repository.TrainRepository;
import in.yograj.learnspringboot.railway_booking.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.time.LocalTime;

@SpringBootApplication
public class RailwayBookingApplication {

    // Startup se admin user ensure hota hai taaki admin endpoint authentication aur authorization ka live testing ho sake.
    @Bean
    @ConditionalOnProperty(name = "railway.data-seed.admin.enabled", havingValue = "true", matchIfMissing = false)
    public CommandLineRunner seedAdminUser(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${railway.data-seed.admin.password:${SEED_ADMIN_PASSWORD:}}") String configuredPassword) {
        if (configuredPassword == null || configuredPassword.isBlank()) {
            throw new IllegalStateException(
                    "Admin user seeding is enabled ('railway.data-seed.admin.enabled=true' or 'SEED_ADMIN_USER_ENABLED=true'), "
                            + "but SEED_ADMIN_PASSWORD is missing or blank. A non-blank password must be configured.");
        }
        return args -> {
            String username = "admin";
            if (!userRepository.existsByUsername(username)) {
                User admin = new User();
                admin.setUsername(username);
                admin.setFullName("System Admin");
                admin.setEmail("admin@railwaybooking.local");
                admin.setPhoneNumber("0000000000");
                admin.setPassword(passwordEncoder.encode(configuredPassword.trim()));
                admin.setStatus("ACTIVE");
                admin.setRole("ROLE_ADMIN");
                userRepository.save(admin);
            }
        };
    }

    @Bean
    @ConditionalOnProperty(name = "railway.data-seed.journey.enabled", havingValue = "true", matchIfMissing = false)
    public CommandLineRunner seedJourneyReferenceData(
            StationRepository stationRepository,
            TrainRepository trainRepository,
            CoachRepository coachRepository,
            SeatRepository seatRepository,
            JourneyRepository journeyRepository,
            JourneySeatInventoryRepository journeySeatInventoryRepository,
            JourneyStopRepository journeyStopRepository) {
        return args -> {
            Station fromStation = stationRepository.findByStationCode("NDLS")
                    .orElseGet(() -> {
                        Station station = new Station();
                        station.setStationCode("NDLS");
                        station.setStationName("New Delhi");
                        station.setCity("New Delhi");
                        station.setState("Delhi");
                        return stationRepository.save(station);
                    });

            Station toStation = stationRepository.findByStationCode("BCT")
                    .orElseGet(() -> {
                        Station station = new Station();
                        station.setStationCode("BCT");
                        station.setStationName("Mumbai Central");
                        station.setCity("Mumbai");
                        station.setState("Maharashtra");
                        return stationRepository.save(station);
                    });

            Train train = trainRepository.findByTrainNumber("12190")
                    .orElseGet(() -> {
                        Train newTrain = new Train();
                        newTrain.setTrainNumber("12190");
                        newTrain.setTrainName("August Kranti Rajdhani Express");
                        newTrain.setStatus("ACTIVE");
                        return trainRepository.save(newTrain);
                    });

            Coach coach = coachRepository.findByTrain_TrainIdAndCoachNumber(train.getTrainId(), "B1")
                    .orElseGet(() -> {
                        Coach newCoach = new Coach();
                        newCoach.setTrain(train);
                        newCoach.setCoachNumber("B1");
                        newCoach.setCoachType("SECOND_SEATING");
                        newCoach.setSeatCapacity(4);
                        newCoach.setStatus("ACTIVE");
                        return coachRepository.save(newCoach);
                    });

            LocalDate journeyDate = LocalDate.now().plusDays(2);
            boolean exists = journeyRepository.existsByTrain_TrainIdAndFromStation_StationIdAndToStation_StationIdAndJourneyDate(
                    train.getTrainId(), fromStation.getStationId(), toStation.getStationId(), journeyDate);

            if (!exists) {
                Journey journey = new Journey();
                journey.setTrain(train);
                journey.setFromStation(fromStation);
                journey.setToStation(toStation);
                journey.setJourneyDate(journeyDate);
                journey.setDepartureTime(LocalTime.of(20, 30));
                journey.setArrivalTime(LocalTime.of(8, 45));
                Journey savedJourney = journeyRepository.save(journey);
                seedJourneyStops(savedJourney, fromStation, toStation, journeyStopRepository);

                for (int seatNumber = 1; seatNumber <= coach.getSeatCapacity(); seatNumber++) {
                    final int currentSeatNumber = seatNumber;
                    Seat seat = seatRepository.findByCoach_CoachIdAndSeatNumber(coach.getCoachId(), currentSeatNumber)
                            .orElseGet(() -> {
                                Seat newSeat = new Seat();
                                newSeat.setCoach(coach);
                                newSeat.setSeatNumber(currentSeatNumber);
                                newSeat.setSeatType(coach.getCoachType());
                                newSeat.setStatus("AVAILABLE");
                                return seatRepository.save(newSeat);
                            });

                    if (!journeySeatInventoryRepository.existsByJourney_JourneyIdAndSeat_SeatId(savedJourney.getJourneyId(), seat.getSeatId())) {
                        JourneySeatInventory inventory = new JourneySeatInventory();
                        inventory.setJourney(savedJourney);
                        inventory.setCoach(coach);
                        inventory.setSeat(seat);
                        inventory.setStatus("AVAILABLE");
                        inventory.setUpdatedAt(java.time.LocalDateTime.now());
                        journeySeatInventoryRepository.save(inventory);
                    }
                }
            } else {
                journeyRepository.findByJourneyDate(journeyDate).stream()
                        .filter(journey -> journey.getTrain().getTrainId().equals(train.getTrainId()))
                        .filter(journey -> journey.getFromStation().getStationId().equals(fromStation.getStationId()))
                        .filter(journey -> journey.getToStation().getStationId().equals(toStation.getStationId()))
                        .findFirst()
                        .filter(journey -> !journeyStopRepository.existsByJourney_JourneyId(journey.getJourneyId()))
                        .ifPresent(journey -> seedJourneyStops(
                                journey, fromStation, toStation, journeyStopRepository));
            }
        };
    }

    private void seedJourneyStops(Journey journey, Station fromStation, Station toStation,
                                  JourneyStopRepository journeyStopRepository) {
        java.time.LocalDateTime departureAt = java.time.LocalDateTime.of(
                journey.getJourneyDate(), journey.getDepartureTime());
        java.time.LocalDate arrivalDate = journey.getArrivalTime().isBefore(journey.getDepartureTime())
                ? journey.getJourneyDate().plusDays(1) : journey.getJourneyDate();
        java.time.LocalDateTime arrivalAt = java.time.LocalDateTime.of(arrivalDate, journey.getArrivalTime());

        JourneyStop origin = new JourneyStop();
        origin.setJourney(journey);
        origin.setStation(fromStation);
        origin.setStopSequence(1);
        origin.setDepartureAt(departureAt);

        JourneyStop destination = new JourneyStop();
        destination.setJourney(journey);
        destination.setStation(toStation);
        destination.setStopSequence(2);
        destination.setArrivalAt(arrivalAt);
        journeyStopRepository.saveAll(java.util.List.of(origin, destination));
    }

    public static void main(String[] args) {
        SpringApplication.run(RailwayBookingApplication.class, args);
    }
}
