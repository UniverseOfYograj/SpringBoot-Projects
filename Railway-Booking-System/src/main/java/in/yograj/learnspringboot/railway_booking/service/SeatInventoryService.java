package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.dto.BookingMode;
import in.yograj.learnspringboot.railway_booking.dto.CoachAvailabilityDTO;
import in.yograj.learnspringboot.railway_booking.dto.SeatAvailabilityResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.TravelClassAvailabilityDTO;
import in.yograj.learnspringboot.railway_booking.entity.*;
import in.yograj.learnspringboot.railway_booking.exception.BadRequestException;
import in.yograj.learnspringboot.railway_booking.exception.ResourceNotFoundException;
import in.yograj.learnspringboot.railway_booking.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SeatInventoryService {

    private static final String AVAILABLE = "AVAILABLE";
    private static final String HOLD = "HOLD";
    private static final String BOOKED = "BOOKED";
    private static final String BLOCKED = "BLOCKED";
    private static final String EXPIRED = "EXPIRED";
    private static final String RELEASED = "RELEASED";

    private final JourneySeatInventoryRepository inventoryRepository;
    private final JourneyRepository journeyRepository;
    private final JourneyStopRepository journeyStopRepository;
    private final SeatAllocationRepository allocationRepository;
    private final CoachRepository coachRepository;
    private final SeatRepository seatRepository;
    private final UserRepository userRepository;
    private final FareCalculationService fareCalculationService;

    @Autowired
    public SeatInventoryService(JourneySeatInventoryRepository inventoryRepository,
                                JourneyRepository journeyRepository,
                                JourneyStopRepository journeyStopRepository,
                                SeatAllocationRepository allocationRepository,
                                CoachRepository coachRepository,
                                SeatRepository seatRepository,
                                UserRepository userRepository,
                                @Autowired(required = false) FareCalculationService fareCalculationService) {
        this.inventoryRepository = inventoryRepository;
        this.journeyRepository = journeyRepository;
        this.journeyStopRepository = journeyStopRepository;
        this.allocationRepository = allocationRepository;
        this.coachRepository = coachRepository;
        this.seatRepository = seatRepository;
        this.userRepository = userRepository;
        this.fareCalculationService = fareCalculationService;
    }

    public SeatInventoryService(JourneySeatInventoryRepository inventoryRepository,
                                JourneyRepository journeyRepository,
                                JourneyStopRepository journeyStopRepository,
                                SeatAllocationRepository allocationRepository,
                                CoachRepository coachRepository,
                                SeatRepository seatRepository,
                                UserRepository userRepository) {
        this(inventoryRepository, journeyRepository, journeyStopRepository, allocationRepository,
                coachRepository, seatRepository, userRepository, null);
    }

    @Transactional(readOnly = true)
    public List<SeatAvailabilityResponseDTO> getJourneySeatAvailability(
            Long journeyId, Long boardingStopId, Long alightingStopId) {
        return getJourneySeatAvailability(journeyId, boardingStopId, alightingStopId, null, null);
    }

    @Transactional(readOnly = true)
    public List<SeatAvailabilityResponseDTO> getJourneySeatAvailability(
            Long journeyId, Long boardingStopId, Long alightingStopId, String travelClass, String coachNumber) {
        ensureJourneyExists(journeyId);
        JourneySegment segment = boardingStopId == null && alightingStopId == null
                ? null : resolveSegment(journeyId, boardingStopId, alightingStopId);

        return inventoryRepository.findByJourney_JourneyId(journeyId).stream()
                .filter(inv -> travelClass == null || travelClass.isBlank() ||
                        (inv.getCoach() != null && travelClass.trim().equalsIgnoreCase(inv.getCoach().getCoachType())))
                .filter(inv -> coachNumber == null || coachNumber.isBlank() ||
                        (inv.getCoach() != null && coachNumber.trim().equalsIgnoreCase(inv.getCoach().getCoachNumber())))
                .map(inventory -> mapAvailability(inventory, segment))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TravelClassAvailabilityDTO> getJourneyClassesAvailability(
            Long journeyId, Long boardingStopId, Long alightingStopId) {
        ensureJourneyExists(journeyId);
        JourneySegment segment = boardingStopId == null && alightingStopId == null
                ? null : resolveSegment(journeyId, boardingStopId, alightingStopId);

        // Strictly scoped to the selected journey
        List<JourneySeatInventory> inventories = inventoryRepository.findByJourney_JourneyId(journeyId);

        // Map each inventory to its segment availability DTO
        Map<Long, SeatAvailabilityResponseDTO> seatDtoMap = inventories.stream()
                .collect(Collectors.toMap(
                        JourneySeatInventory::getInventoryId,
                        inv -> mapAvailability(inv, segment)
                ));

        // Group inventories by Coach preserving deterministic order
        Map<Coach, List<JourneySeatInventory>> coachInventoriesMap = inventories.stream()
                .collect(Collectors.groupingBy(
                        JourneySeatInventory::getCoach,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<CoachAvailabilityDTO> coachDtos = new ArrayList<>();
        for (Map.Entry<Coach, List<JourneySeatInventory>> entry : coachInventoriesMap.entrySet()) {
            Coach coach = entry.getKey();
            List<JourneySeatInventory> coachInvs = entry.getValue();

            List<SeatAvailabilityResponseDTO> coachSeats = coachInvs.stream()
                    .map(inv -> seatDtoMap.get(inv.getInventoryId()))
                    .sorted(Comparator.comparing(SeatAvailabilityResponseDTO::seatNumber))
                    .toList();

            int available = (int) coachSeats.stream().filter(s -> AVAILABLE.equalsIgnoreCase(s.status())).count();
            int held = (int) coachSeats.stream().filter(s -> HOLD.equalsIgnoreCase(s.status())).count();
            int booked = (int) coachSeats.stream().filter(s -> BOOKED.equalsIgnoreCase(s.status())).count();

            coachDtos.add(new CoachAvailabilityDTO(
                    coach.getCoachId(),
                    coach.getCoachNumber(),
                    coach.getCoachType(),
                    coachSeats.size(),
                    available,
                    held,
                    booked,
                    coachSeats
            ));
        }

        // Group CoachAvailabilityDTO by travel class (coachType)
        Map<String, List<CoachAvailabilityDTO>> classCoachesMap = coachDtos.stream()
                .collect(Collectors.groupingBy(
                        c -> c.coachType() != null ? c.coachType().toUpperCase().trim() : "OTHER",
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<String> priorityOrder = List.of("1A", "2A", "3A", "3E", "CC", "SL", "2S");

        List<TravelClassAvailabilityDTO> classDtos = new ArrayList<>();
        for (Map.Entry<String, List<CoachAvailabilityDTO>> entry : classCoachesMap.entrySet()) {
            String classCode = entry.getKey();
            List<CoachAvailabilityDTO> coachesInClass = entry.getValue();

            int totalSeats = coachesInClass.stream().mapToInt(CoachAvailabilityDTO::totalSeats).sum();
            int availableSeats = coachesInClass.stream().mapToInt(CoachAvailabilityDTO::availableSeats).sum();
            int heldSeats = coachesInClass.stream().mapToInt(CoachAvailabilityDTO::heldSeats).sum();
            int bookedSeats = coachesInClass.stream().mapToInt(CoachAvailabilityDTO::bookedSeats).sum();

            BigDecimal classBaseFare = null;
            if (fareCalculationService != null) {
                try {
                    classBaseFare = fareCalculationService.calculateFareQuote(
                            journeyId, classCode, 1, boardingStopId, alightingStopId).perPassengerFare().total();
                } catch (Exception ignored) {
                    classBaseFare = null;
                }
            }

            classDtos.add(new TravelClassAvailabilityDTO(
                    classCode,
                    getClassName(classCode),
                    totalSeats,
                    availableSeats,
                    heldSeats,
                    bookedSeats,
                    classBaseFare,
                    coachesInClass
            ));
        }

        // Sort by priority order, then alphabetical
        classDtos.sort((a, b) -> {
            int idxA = priorityOrder.indexOf(a.classCode());
            int idxB = priorityOrder.indexOf(b.classCode());
            if (idxA != -1 && idxB != -1) return Integer.compare(idxA, idxB);
            if (idxA != -1) return -1;
            if (idxB != -1) return 1;
            return a.classCode().compareTo(b.classCode());
        });

        return classDtos;
    }

    public static String getClassName(String classCode) {
        if (classCode == null) return "Unknown";
        return switch (classCode.toUpperCase().trim()) {
            case "SL" -> "Sleeper";
            case "3A" -> "AC 3 Tier";
            case "2A" -> "AC 2 Tier";
            case "1A" -> "AC First Class";
            case "3E" -> "AC 3 Economy";
            case "CC" -> "AC Chair Car";
            case "2S" -> "Second Seating";
            case "EC" -> "Executive Chair Car";
            case "GEN", "GENERAL" -> "General Unreserved";
            default -> classCode;
        };
    }

    @Transactional(readOnly = true)
    public List<SeatAvailabilityResponseDTO> getAvailableSeats(
            Long journeyId, Long boardingStopId, Long alightingStopId) {
        return getJourneySeatAvailability(journeyId, boardingStopId, alightingStopId).stream()
                .filter(seat -> AVAILABLE.equals(seat.status()))
                .toList();
    }

    @Transactional
    public List<SeatAvailabilityResponseDTO> holdSeats(
            Long journeyId, List<Long> seatIds, Integer holdMinutes,
            Long boardingStopId, Long alightingStopId,
            String travelClass, String coachNumber, String username) {
        return holdSeatsBatch(journeyId, seatIds, holdMinutes, boardingStopId, alightingStopId,
                travelClass, coachNumber, username, BookingMode.GENERAL, null);
    }

    @Transactional
    public List<SeatAvailabilityResponseDTO> holdSeatsBatch(
            Long journeyId, List<Long> seatIds, Integer holdMinutes,
            Long boardingStopId, Long alightingStopId,
            String travelClass, String coachNumber, String username) {
        return holdSeatsBatch(journeyId, seatIds, holdMinutes, boardingStopId, alightingStopId,
                travelClass, coachNumber, username, BookingMode.GENERAL, null);
    }

    @Transactional
    public List<SeatAvailabilityResponseDTO> holdSeatsBatch(
            Long journeyId, List<Long> seatIds, Integer holdMinutes,
            Long boardingStopId, Long alightingStopId,
            String travelClass, String coachNumber, String username,
            BookingMode bookingMode, String admissionEntryId) {
        if (seatIds == null || seatIds.isEmpty()) {
            throw new BadRequestException("At least one seat ID must be specified for batch hold");
        }
        if (new HashSet<>(seatIds).size() != seatIds.size()) {
            throw new BadRequestException("Duplicate seats are not allowed in hold request");
        }
        if (seatIds.size() > 6) {
            throw new BadRequestException("Cannot hold more than 6 seats in a single request");
        }
        List<Long> sortedSeatIds = new ArrayList<>(seatIds);
        Collections.sort(sortedSeatIds);

        List<SeatAvailabilityResponseDTO> responses = new ArrayList<>();
        for (Long seatId : sortedSeatIds) {
            responses.add(holdSeat(journeyId, seatId, holdMinutes, boardingStopId, alightingStopId,
                    travelClass, coachNumber, username, bookingMode, admissionEntryId));
        }
        return responses;
    }

    @Transactional
    public SeatAvailabilityResponseDTO holdSeat(Long journeyId, Long seatId, Integer holdMinutes,
                                                 Long boardingStopId, Long alightingStopId, String username) {
        return holdSeat(journeyId, seatId, holdMinutes, boardingStopId, alightingStopId,
                null, null, username, BookingMode.GENERAL, null);
    }

    @Transactional
    public SeatAvailabilityResponseDTO holdSeat(Long journeyId, Long seatId, Integer holdMinutes,
                                                 Long boardingStopId, Long alightingStopId, String username,
                                                 BookingMode bookingMode, String admissionEntryId) {
        return holdSeat(journeyId, seatId, holdMinutes, boardingStopId, alightingStopId,
                null, null, username, bookingMode, admissionEntryId);
    }

    @Transactional
    public SeatAvailabilityResponseDTO holdSeat(Long journeyId, Long seatId, Integer holdMinutes,
                                                 Long boardingStopId, Long alightingStopId,
                                                 String travelClass, String coachNumber, String username) {
        return holdSeat(journeyId, seatId, holdMinutes, boardingStopId, alightingStopId,
                travelClass, coachNumber, username, BookingMode.GENERAL, null);
    }

    @Transactional
    public SeatAvailabilityResponseDTO holdSeat(Long journeyId, Long seatId, Integer holdMinutes,
                                                 Long boardingStopId, Long alightingStopId,
                                                 String travelClass, String coachNumber, String username,
                                                 BookingMode bookingMode, String admissionEntryId) {
        if (holdMinutes == null || holdMinutes < 1) {
            throw new BadRequestException("Hold duration in minutes is required and must be >= 1");
        }
        User holdOwner = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));

        // Same run-seat row par lock lekar overlap check + hold insert serialize kiya hai.
        JourneySeatInventory inventory = inventoryRepository.findByJourneyAndSeatForUpdate(journeyId, seatId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Seat not found for journey: journeyId=" + journeyId + ", seatId=" + seatId));

        // Validate travel class match if provided
        if (travelClass != null && !travelClass.isBlank()) {
            String expectedClass = travelClass.trim();
            String actualClass = inventory.getCoach() != null ? inventory.getCoach().getCoachType() : null;
            if (actualClass == null || !actualClass.equalsIgnoreCase(expectedClass)) {
                throw new BadRequestException(
                        "Seat #" + inventory.getSeat().getSeatNumber() + " belongs to class "
                                + (actualClass != null ? actualClass : "UNKNOWN")
                                + ", not requested class " + expectedClass);
            }
        }

        // Validate coach match if provided
        if (coachNumber != null && !coachNumber.isBlank()) {
            String expectedCoach = coachNumber.trim();
            String actualCoach = inventory.getCoach() != null ? inventory.getCoach().getCoachNumber() : null;
            if (actualCoach == null || !actualCoach.equalsIgnoreCase(expectedCoach)) {
                throw new BadRequestException(
                        "Seat #" + inventory.getSeat().getSeatNumber() + " belongs to coach "
                                + (actualCoach != null ? actualCoach : "UNKNOWN")
                                + ", not requested coach " + expectedCoach);
            }
        }

        JourneySegment segment = resolveSegment(journeyId, boardingStopId, alightingStopId);
        normalizeLegacyHold(inventory);

        if (BLOCKED.equalsIgnoreCase(inventory.getStatus())
                || BOOKED.equalsIgnoreCase(inventory.getStatus())) {
            throw new BadRequestException("Seat inventory is blocked for this journey");
        }
        if (HOLD.equalsIgnoreCase(inventory.getStatus())) {
            throw new BadRequestException("Legacy seat hold cannot be safely assigned to a user");
        }

        LocalDateTime now = LocalDateTime.now();
        List<SeatAllocation> allocations = allocationRepository
                .findByInventory_InventoryId(inventory.getInventoryId());
        List<SeatAllocation> expiredAllocations = new ArrayList<>();
        for (SeatAllocation allocation : allocations) {
            if (HOLD.equalsIgnoreCase(allocation.getStatus())
                    && (allocation.getHoldExpiresAt() == null || !allocation.getHoldExpiresAt().isAfter(now))) {
                allocation.setStatus(EXPIRED);
                allocation.setUpdatedAt(now);
                expiredAllocations.add(allocation);
            }
        }
        if (!expiredAllocations.isEmpty()) {
            allocationRepository.saveAllAndFlush(expiredAllocations);
        }
        if (allocations.stream().anyMatch(allocation -> isBlocking(allocation, segment, now))) {
            throw new BadRequestException("Selected seat is held or booked for an overlapping segment");
        }

        SeatAllocation allocation = new SeatAllocation();
        allocation.setInventory(inventory);
        allocation.setBoardingStop(segment.boardingStop());
        allocation.setAlightingStop(segment.alightingStop());
        allocation.setFromStopSeq(segment.boardingStop().getStopSequence());
        allocation.setToStopSeq(segment.alightingStop().getStopSequence());
        allocation.setHoldOwner(holdOwner);
        allocation.setBookingMode(bookingMode);
        allocation.setAdmissionEntryId(admissionEntryId);
        allocation.setStatus(HOLD);
        allocation.setHoldExpiresAt(now.plusMinutes(holdMinutes));
        allocation.setCreatedAt(now);
        allocation.setUpdatedAt(now);
        return mapAllocation(inventory, allocationRepository.save(allocation));
    }

    @Transactional
    public SeatAvailabilityResponseDTO releaseSeat(Long journeyId, Long seatId) {
        JourneySeatInventory inventory = inventoryRepository.findByJourneyAndSeatForUpdate(journeyId, seatId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Seat inventory not found for journeyId=" + journeyId + ", seatId=" + seatId));
        LocalDateTime now = LocalDateTime.now();
        allocationRepository.findByInventory_InventoryIdAndStatus(inventory.getInventoryId(), HOLD)
                .forEach(allocation -> {
                    allocation.setStatus(RELEASED);
                    allocation.setUpdatedAt(now);
                });
        if (HOLD.equalsIgnoreCase(inventory.getStatus())) {
            inventory.setStatus(AVAILABLE);
            inventory.setHoldExpiresAt(null);
            inventory.setHoldOwner(null);
            inventory.setUpdatedAt(now);
            inventoryRepository.save(inventory);
        }
        if (BLOCKED.equalsIgnoreCase(inventory.getStatus())) {
            inventory.setStatus(AVAILABLE);
            inventory.setUpdatedAt(now);
            inventoryRepository.save(inventory);
        }
        return mapAvailability(inventory, null);
    }

    @Transactional
    public void normalizeLegacyHold(JourneySeatInventory inventory) {
        if (!HOLD.equalsIgnoreCase(inventory.getStatus())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (inventory.getHoldExpiresAt() != null && !inventory.getHoldExpiresAt().isAfter(now)) {
            inventory.setStatus(AVAILABLE);
            inventory.setHoldExpiresAt(null);
            inventory.setHoldOwner(null);
            inventory.setUpdatedAt(now);
            inventoryRepository.save(inventory);
            return;
        }
        if (inventory.getHoldOwner() == null) {
            return;
        }

        JourneySegment fullRun = resolveSegment(inventory.getJourney().getJourneyId(), null, null);
        SeatAllocation legacyAllocation = new SeatAllocation();
        legacyAllocation.setInventory(inventory);
        legacyAllocation.setBoardingStop(fullRun.boardingStop());
        legacyAllocation.setAlightingStop(fullRun.alightingStop());
        legacyAllocation.setFromStopSeq(fullRun.boardingStop().getStopSequence());
        legacyAllocation.setToStopSeq(fullRun.alightingStop().getStopSequence());
        legacyAllocation.setHoldOwner(inventory.getHoldOwner());
        legacyAllocation.setStatus(HOLD);
        legacyAllocation.setHoldExpiresAt(inventory.getHoldExpiresAt());
        legacyAllocation.setCreatedAt(now);
        legacyAllocation.setUpdatedAt(now);
        allocationRepository.save(legacyAllocation);

        inventory.setStatus(AVAILABLE);
        inventory.setHoldExpiresAt(null);
        inventory.setHoldOwner(null);
        inventory.setUpdatedAt(now);
        inventoryRepository.save(inventory);
    }

    @Transactional
    public List<SeatAvailabilityResponseDTO> seedInventoryForJourney(Long journeyId) {
        Journey journey = journeyRepository.findById(journeyId)
                .orElseThrow(() -> new ResourceNotFoundException("Journey not found with id: " + journeyId));
        List<Coach> coaches = coachRepository.findByTrain_TrainId(journey.getTrain().getTrainId());
        if (coaches.isEmpty()) {
            Coach coach = new Coach();
            coach.setTrain(journey.getTrain());
            coach.setCoachNumber("B1");
            coach.setCoachType("SECOND_SEATING");
            coach.setSeatCapacity(4);
            coach.setStatus("ACTIVE");
            coaches = List.of(coachRepository.save(coach));
        }

        List<SeatAvailabilityResponseDTO> response = new ArrayList<>();
        for (Coach coach : coaches) {
            List<Seat> seats = seatRepository.findByCoach_CoachId(coach.getCoachId());
            if (seats.isEmpty()) {
                for (int number = 1; number <= coach.getSeatCapacity(); number++) {
                    Seat seat = new Seat();
                    seat.setCoach(coach);
                    seat.setSeatNumber(number);
                    seat.setSeatType(coach.getCoachType());
                    seat.setStatus(AVAILABLE);
                    seats.add(seatRepository.save(seat));
                }
            }
            for (Seat seat : seats) {
                if (!inventoryRepository.existsByJourney_JourneyIdAndSeat_SeatId(journeyId, seat.getSeatId())) {
                    JourneySeatInventory inventory = new JourneySeatInventory();
                    inventory.setJourney(journey);
                    inventory.setCoach(coach);
                    inventory.setSeat(seat);
                    inventory.setStatus(AVAILABLE);
                    inventory.setUpdatedAt(LocalDateTime.now());
                    response.add(mapAvailability(inventoryRepository.save(inventory), null));
                }
            }
        }
        return response.isEmpty() ? getJourneySeatAvailability(journeyId, null, null) : response;
    }

    @Transactional(readOnly = true)
    public JourneySegment resolveSegment(Long journeyId, Long boardingStopId, Long alightingStopId) {
        ensureJourneyExists(journeyId);
        List<JourneyStop> stops = journeyStopRepository
                .findByJourney_JourneyIdOrderByStopSequence(journeyId);
        if (stops.size() < 2) {
            throw new BadRequestException("Journey must have at least two dated stops before seat allocation");
        }
        if (boardingStopId == null && alightingStopId == null) {
            return new JourneySegment(stops.get(0), stops.get(stops.size() - 1));
        }
        if (boardingStopId == null || alightingStopId == null) {
            throw new BadRequestException("Both boardingJourneyStopId and alightingJourneyStopId are required");
        }

        JourneyStop boarding = journeyStopRepository.findById(boardingStopId)
                .filter(stop -> stop.getJourney().getJourneyId().equals(journeyId))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Boarding stop not found for journeyId=" + journeyId));
        JourneyStop alighting = journeyStopRepository.findById(alightingStopId)
                .filter(stop -> stop.getJourney().getJourneyId().equals(journeyId))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Alighting stop not found for journeyId=" + journeyId));
        if (boarding.getStopSequence() >= alighting.getStopSequence()) {
            throw new BadRequestException("Boarding stop must precede alighting stop");
        }
        return new JourneySegment(boarding, alighting);
    }

    @Transactional(readOnly = true)
    public boolean isBlocking(SeatAllocation allocation, JourneySegment segment, LocalDateTime now) {
        if (BOOKED.equalsIgnoreCase(allocation.getStatus())) {
            return overlaps(allocation, segment);
        }
        return HOLD.equalsIgnoreCase(allocation.getStatus())
                && (allocation.getHoldExpiresAt() == null || allocation.getHoldExpiresAt().isAfter(now))
                && overlaps(allocation, segment);
    }

    public boolean overlaps(SeatAllocation existing, JourneySegment requested) {
        // Intervals half-open hain [boarding, alighting), isliye ek hi station par hand-off allowed hai.
        return existing.getBoardingStop().getStopSequence() < requested.alightingStop().getStopSequence()
                && requested.boardingStop().getStopSequence() < existing.getAlightingStop().getStopSequence();
    }

    private void ensureJourneyExists(Long journeyId) {
        if (!journeyRepository.existsById(journeyId)) {
            throw new ResourceNotFoundException("Journey not found with id: " + journeyId);
        }
    }

    private SeatAvailabilityResponseDTO mapAvailability(JourneySeatInventory inventory, JourneySegment segment) {
        List<SeatAllocation> allocations = allocationRepository
                .findByInventory_InventoryId(inventory.getInventoryId());
        LocalDateTime now = LocalDateTime.now();
        SeatAllocation firstBlocking = allocations.stream()
                .filter(allocation -> {
                    if (segment == null) {
                        return BOOKED.equalsIgnoreCase(allocation.getStatus())
                                || (HOLD.equalsIgnoreCase(allocation.getStatus())
                                && (allocation.getHoldExpiresAt() == null
                                || allocation.getHoldExpiresAt().isAfter(now)));
                    }
                    return isBlocking(allocation, segment, now);
                }).findFirst().orElse(null);

        String status;
        boolean legacyHoldActive = HOLD.equalsIgnoreCase(inventory.getStatus())
                && (inventory.getHoldExpiresAt() == null || inventory.getHoldExpiresAt().isAfter(now));
        if (BLOCKED.equalsIgnoreCase(inventory.getStatus())
                || BOOKED.equalsIgnoreCase(inventory.getStatus())) {
            status = BLOCKED;
        } else if (legacyHoldActive) {
            status = segment == null ? "SEGMENTED" : HOLD;
        } else if (firstBlocking != null) {
            status = segment == null ? "SEGMENTED" : firstBlocking.getStatus();
        } else {
            status = AVAILABLE;
        }

        return new SeatAvailabilityResponseDTO(
                inventory.getInventoryId(),
                inventory.getJourney().getJourneyId(),
                inventory.getCoach().getCoachId(),
                inventory.getCoach().getCoachNumber(),
                inventory.getCoach() != null ? inventory.getCoach().getCoachType() : null,
                inventory.getSeat().getSeatId(),
                inventory.getSeat().getSeatNumber(),
                inventory.getSeat().getSeatType(),
                status,
                firstBlocking == null
                        ? (legacyHoldActive ? inventory.getHoldExpiresAt() : null)
                        : firstBlocking.getHoldExpiresAt(),
                inventory.getUpdatedAt(),
                firstBlocking == null ? null : firstBlocking.getSeatAllocationId(),
                firstBlocking == null ? null : firstBlocking.getBoardingStop().getJourneyStopId(),
                firstBlocking == null ? null : firstBlocking.getAlightingStop().getJourneyStopId()
        );
    }

    private SeatAvailabilityResponseDTO mapAllocation(JourneySeatInventory inventory, SeatAllocation allocation) {
        return new SeatAvailabilityResponseDTO(
                inventory.getInventoryId(),
                inventory.getJourney().getJourneyId(),
                inventory.getCoach().getCoachId(),
                inventory.getCoach().getCoachNumber(),
                inventory.getCoach() != null ? inventory.getCoach().getCoachType() : null,
                inventory.getSeat().getSeatId(),
                inventory.getSeat().getSeatNumber(),
                inventory.getSeat().getSeatType(),
                allocation.getStatus(),
                allocation.getHoldExpiresAt(),
                allocation.getUpdatedAt(),
                allocation.getSeatAllocationId(),
                allocation.getBoardingStop().getJourneyStopId(),
                allocation.getAlightingStop().getJourneyStopId()
        );
    }

    public record JourneySegment(JourneyStop boardingStop, JourneyStop alightingStop) {
    }
}
