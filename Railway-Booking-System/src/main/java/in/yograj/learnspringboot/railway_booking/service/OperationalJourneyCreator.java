package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.entity.*;
import in.yograj.learnspringboot.railway_booking.repository.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class OperationalJourneyCreator {

    private final JourneyRepository journeyRepository;
    private final JourneyStopRepository journeyStopRepository;
    private final CoachRepository coachRepository;
    private final SeatRepository seatRepository;
    private final JourneySeatInventoryRepository inventoryRepository;

    public OperationalJourneyCreator(
            JourneyRepository journeyRepository,
            JourneyStopRepository journeyStopRepository,
            CoachRepository coachRepository,
            SeatRepository seatRepository,
            JourneySeatInventoryRepository inventoryRepository) {
        this.journeyRepository = journeyRepository;
        this.journeyStopRepository = journeyStopRepository;
        this.coachRepository = coachRepository;
        this.seatRepository = seatRepository;
        this.inventoryRepository = inventoryRepository;
    }

    private static record CoachTemplate(String coachNumber, String classCode, int capacity, String[] berthTypes) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Journey createOperationalJourneyInNewTransaction(
            Train train,
            TrainRoute route,
            List<TrainStop> routeStops,
            LocalDate date) {

        // Check if another concurrent thread committed just prior to this new transaction starting
        Optional<Journey> existing = journeyRepository.findByTrain_TrainIdAndJourneyDate(train.getTrainId(), date);
        if (existing.isPresent()) {
            return existing.get();
        }

        TrainStop first = routeStops.get(0);
        TrainStop last = routeStops.get(routeStops.size() - 1);

        Journey journey = new Journey();
        journey.setTrain(train);
        journey.setTrainRoute(route);
        journey.setFromStation(first.getStation());
        journey.setToStation(last.getStation());
        journey.setJourneyDate(date);
        journey.setDepartureTime(first.getDepartureTime() != null ? first.getDepartureTime() : LocalTime.of(8, 0));
        journey.setArrivalTime(last.getArrivalTime() != null ? last.getArrivalTime() : LocalTime.of(20, 0));
        journey.setOperatingDaysSource("DAILY".equalsIgnoreCase(train.getOperatingDays()) ? "DEMO_FALLBACK_DAILY" : "SCHEDULED");

        // Flush immediately so any concurrent uk_journeys_train_date violation throws here and triggers clean rollback of this isolated transaction
        Journey saved = journeyRepository.saveAndFlush(journey);

        List<JourneyStop> datedStops = buildJourneyStops(saved, routeStops);
        journeyStopRepository.saveAllAndFlush(datedStops);

        seedMultiClassInventory(saved);

        return saved;
    }

    public void seedMultiClassInventory(Journey journey) {
        Train train = journey.getTrain();
        List<Coach> existingCoaches = coachRepository.findByTrain_TrainId(train.getTrainId());

        if (existingCoaches.isEmpty()) {
            String profile = train.getClassProfile();
            String nameUpper = train.getTrainName() != null ? train.getTrainName().toUpperCase() : "";

            List<CoachTemplate> templates;
            if ("RAJDHANI".equalsIgnoreCase(profile) || nameUpper.contains("RAJDHANI")) {
                templates = List.of(
                        new CoachTemplate("H1", "1A", 4, new String[]{"LOWER", "UPPER", "LOWER", "UPPER"}),
                        new CoachTemplate("A1", "2A", 8, new String[]{"LOWER", "UPPER", "SIDE_LOWER", "SIDE_UPPER", "LOWER", "UPPER", "SIDE_LOWER", "SIDE_UPPER"}),
                        new CoachTemplate("B1", "3A", 12, new String[]{"LOWER", "MIDDLE", "UPPER", "LOWER", "MIDDLE", "UPPER", "SIDE_LOWER", "SIDE_UPPER", "LOWER", "MIDDLE", "UPPER", "SIDE_LOWER"})
                );
            } else if ("SHATABDI".equalsIgnoreCase(profile) || "VANDE_BHARAT".equalsIgnoreCase(profile)
                    || nameUpper.contains("SHATABDI") || nameUpper.contains("VANDE BHARAT")) {
                templates = List.of(
                        new CoachTemplate("E1", "EC", 8, new String[]{"WINDOW", "AISLE", "AISLE", "WINDOW", "WINDOW", "AISLE", "AISLE", "WINDOW"}),
                        new CoachTemplate("C1", "CC", 16, new String[]{"WINDOW", "MIDDLE", "AISLE", "AISLE", "WINDOW", "WINDOW", "MIDDLE", "AISLE", "AISLE", "WINDOW", "WINDOW", "MIDDLE", "AISLE", "AISLE", "WINDOW", "WINDOW"})
                );
            } else if ("GARIB_RATH".equalsIgnoreCase(profile) || nameUpper.contains("GARIB RATH")) {
                templates = List.of(
                        new CoachTemplate("G1", "3A", 12, new String[]{"LOWER", "MIDDLE", "UPPER", "LOWER", "MIDDLE", "UPPER", "SIDE_LOWER", "SIDE_UPPER", "LOWER", "MIDDLE", "UPPER", "SIDE_LOWER"}),
                        new CoachTemplate("J1", "3E", 12, new String[]{"LOWER", "MIDDLE", "UPPER", "LOWER", "MIDDLE", "UPPER", "SIDE_LOWER", "SIDE_UPPER", "LOWER", "MIDDLE", "UPPER", "SIDE_LOWER"})
                );
            } else {
                // Default Express / Superfast: 2A, 3A, SL, 2S
                templates = List.of(
                        new CoachTemplate("A1", "2A", 8, new String[]{"LOWER", "UPPER", "SIDE_LOWER", "SIDE_UPPER", "LOWER", "UPPER", "SIDE_LOWER", "SIDE_UPPER"}),
                        new CoachTemplate("B1", "3A", 12, new String[]{"LOWER", "MIDDLE", "UPPER", "LOWER", "MIDDLE", "UPPER", "SIDE_LOWER", "SIDE_UPPER", "LOWER", "MIDDLE", "UPPER", "SIDE_LOWER"}),
                        new CoachTemplate("S1", "SL", 16, new String[]{"LOWER", "MIDDLE", "UPPER", "LOWER", "MIDDLE", "UPPER", "SIDE_LOWER", "SIDE_UPPER", "LOWER", "MIDDLE", "UPPER", "LOWER", "MIDDLE", "UPPER", "SIDE_LOWER", "SIDE_UPPER"}),
                        new CoachTemplate("D1", "2S", 16, new String[]{"WINDOW", "MIDDLE", "AISLE", "AISLE", "WINDOW", "WINDOW", "MIDDLE", "AISLE", "AISLE", "WINDOW", "WINDOW", "MIDDLE", "AISLE", "AISLE", "WINDOW", "WINDOW"})
                );
            }

            for (CoachTemplate t : templates) {
                Optional<Coach> optCoach = coachRepository.findByTrain_TrainIdAndCoachNumber(train.getTrainId(), t.coachNumber());
                Coach coach;
                if (optCoach.isPresent()) {
                    coach = optCoach.get();
                } else {
                    Coach newCoach = new Coach();
                    newCoach.setTrain(train);
                    newCoach.setCoachNumber(t.coachNumber());
                    newCoach.setCoachType(t.classCode());
                    newCoach.setSeatCapacity(t.capacity());
                    newCoach.setStatus("ACTIVE");
                    try {
                        coach = coachRepository.saveAndFlush(newCoach);
                    } catch (DataIntegrityViolationException ex) {
                        coach = coachRepository.findByTrain_TrainIdAndCoachNumber(train.getTrainId(), t.coachNumber()).orElse(null);
                    }
                }

                if (coach != null) {
                    for (int seatNumber = 1; seatNumber <= t.capacity(); seatNumber++) {
                        Optional<Seat> optSeat = seatRepository.findByCoach_CoachIdAndSeatNumber(coach.getCoachId(), seatNumber);
                        if (optSeat.isEmpty()) {
                            Seat seat = new Seat();
                            seat.setCoach(coach);
                            seat.setSeatNumber(seatNumber);
                            String berthType = t.berthTypes()[(seatNumber - 1) % t.berthTypes().length];
                            seat.setSeatType(berthType);
                            seat.setStatus("AVAILABLE");
                            try {
                                seatRepository.saveAndFlush(seat);
                            } catch (DataIntegrityViolationException ignored) {
                            }
                        }
                    }
                }
            }
            existingCoaches = coachRepository.findByTrain_TrainId(train.getTrainId());
        }

        List<JourneySeatInventory> inventoriesToSave = new ArrayList<>();
        for (Coach coach : existingCoaches) {
            List<Seat> seats = seatRepository.findByCoach_CoachId(coach.getCoachId());
            for (Seat seat : seats) {
                if (!inventoryRepository.existsByJourney_JourneyIdAndSeat_SeatId(
                        journey.getJourneyId(), seat.getSeatId())) {
                    JourneySeatInventory inventory = new JourneySeatInventory();
                    inventory.setJourney(journey);
                    inventory.setCoach(coach);
                    inventory.setSeat(seat);
                    inventory.setStatus("AVAILABLE");
                    inventory.setUpdatedAt(LocalDateTime.now());
                    inventoriesToSave.add(inventory);
                }
            }
        }
        if (!inventoriesToSave.isEmpty()) {
            inventoryRepository.saveAllAndFlush(inventoriesToSave);
        }
    }

    private List<JourneyStop> buildJourneyStops(Journey journey, List<TrainStop> routeStops) {
        return routeStops.stream().map(trainStop -> {
            JourneyStop stop = new JourneyStop();
            stop.setJourney(journey);
            stop.setTrainStop(trainStop);
            stop.setStation(trainStop.getStation());
            stop.setStopSequence(trainStop.getStopSequence());
            LocalDate stopDate = journey.getJourneyDate().plusDays(trainStop.getDayOffset());
            stop.setArrivalAt(trainStop.getArrivalTime() == null ? null
                    : LocalDateTime.of(stopDate, trainStop.getArrivalTime()));
            stop.setDepartureAt(trainStop.getDepartureTime() == null ? null
                    : LocalDateTime.of(stopDate, trainStop.getDepartureTime()));
            stop.setDistanceFromOriginKm(trainStop.getDistanceFromOriginKm());
            return stop;
        }).toList();
    }
}
