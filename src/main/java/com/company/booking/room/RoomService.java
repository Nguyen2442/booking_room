package com.company.booking.room;

import com.company.booking.booking.BookingRepository;
import com.company.booking.common.ApiException;
import com.company.booking.common.PageResponse;
import com.company.booking.common.SortGuard;
import com.company.booking.room.RoomDtos.RoomRequest;
import com.company.booking.room.RoomDtos.RoomResponse;
import java.time.Clock;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RoomService {

    private static final Set<String> SORTABLE = Set.of("name", "capacity", "location", "createdAt");

    private final RoomRepository rooms;
    private final BookingRepository bookings;
    private final Clock clock;

    /** Equipment tags are case-insensitive: "Projector" == "projector" == "PROJECTOR". */
    public static Set<String> normalizeEquipment(Set<String> raw) {
        if (raw == null) {
            return new HashSet<>();
        }
        return raw.stream().map(s -> s.trim().toUpperCase(Locale.ROOT)).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(HashSet::new));
    }

    @Transactional
    public RoomResponse create(RoomRequest req) {
        String name = req.name().trim();
        if (rooms.existsByNameIgnoreCase(name)) {
            throw nameTaken();
        }
        Room r = new Room();
        apply(r, req);
        return save(r);
    }

    @Transactional
    public RoomResponse update(UUID id, RoomRequest req) {
        Room r = rooms.findById(id).orElseThrow(RoomService::notFound);
        if (rooms.existsByNameIgnoreCaseAndIdNot(req.name().trim(), id)) {
            throw nameTaken();
        }
        apply(r, req);
        return save(r);
    }

    @Transactional(readOnly = true)
    public RoomResponse get(UUID id, boolean admin) {
        Room r = rooms.findById(id).orElseThrow(RoomService::notFound);
        // Regular users cannot tell a closed room from a non-existent one.
        if (!r.isActive() && !admin) {
            throw notFound();
        }
        return RoomResponse.from(r);
    }

    @Transactional(readOnly = true)
    public PageResponse<RoomResponse> list(
            Integer minCapacity, Set<String> equipment, Boolean active, boolean admin, Pageable pageable) {
        SortGuard.check(pageable, SORTABLE);
        Boolean effectiveActive = admin ? active : Boolean.TRUE;
        return PageResponse.from(rooms.findAll(
                RoomSpecifications.filter(minCapacity, normalizeEquipment(equipment), effectiveActive), pageable)
                .map(RoomResponse::from));
    }

    /** Soft delete. Refused while future bookings exist so nobody turns up at a closed room. */
    @Transactional
    public void deactivate(UUID id) {
        Room r = rooms.findByIdForUpdate(id).orElseThrow(RoomService::notFound);
        if (!r.isActive()) {
            return; // idempotent
        }
        if (bookings.existsFutureConfirmed(id, clock.instant())) {
            throw ApiException.conflict("ROOM_HAS_FUTURE_BOOKINGS",
                    "Room has upcoming bookings; cancel or move them before deactivating");
        }
        r.setActive(false);
        rooms.save(r);
    }

    @Transactional
    public RoomResponse activate(UUID id) {
        Room r = rooms.findById(id).orElseThrow(RoomService::notFound);
        r.setActive(true);
        return RoomResponse.from(rooms.save(r));
    }

    private void apply(Room r, RoomRequest req) {
        r.setName(req.name().trim());
        r.setLocation(req.location() == null ? null : req.location().trim());
        r.setCapacity(req.capacity());
        r.setDescription(req.description());
        r.getEquipment().clear();
        r.getEquipment().addAll(normalizeEquipment(req.equipment()));
    }

    private RoomResponse save(Room r) {
        try {
            return RoomResponse.from(rooms.saveAndFlush(r));
        } catch (DataIntegrityViolationException e) {
            throw nameTaken(); // concurrent create with the same name hit the unique index
        }
    }

    private static ApiException nameTaken() {
        return ApiException.conflict("ROOM_NAME_EXISTS", "A room with this name already exists");
    }

    private static ApiException notFound() {
        return ApiException.notFound("ROOM_NOT_FOUND", "Room not found");
    }
}
