package com.farfartaxi.backend.service;

import com.farfartaxi.backend.model.OfferStatus;
import com.farfartaxi.backend.model.RideEntity;
import com.farfartaxi.backend.model.RideKind;
import com.farfartaxi.backend.model.RideOfferEntity;
import com.farfartaxi.backend.model.Role;
import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.repo.RideOfferRepository;
import com.farfartaxi.backend.repo.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Offer lifecycle: who a ride is offered to and what happens to those offers as the ride moves. Same-world only. */
@Service
public class RideOfferService {
    private static final Set<OfferStatus> REOFFERABLE = EnumSet.of(OfferStatus.WITHDRAWN, OfferStatus.EXPIRED);

    private final RideOfferRepository offers;
    private final UserRepository users;
    private final PushService push;
    private final Clock clock;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;

    public RideOfferService(RideOfferRepository offers, UserRepository users, PushService push, Clock clock) {
        this.offers = offers;
        this.users = users;
        this.push = push;
        this.clock = clock;
    }

    /** Drivers (DRIVER or ADMIN) of the ride's world, excluding the passenger. */
    public List<UserEntity> driverPool(RideEntity ride) {
        Long passengerId = ride.getPassenger().getId();
        return users.findByRoleInAndEnabledTrueAndApprovedTrueAndTest(List.of(Role.DRIVER, Role.ADMIN), ride.isTest())
            .stream().filter(u -> !u.getId().equals(passengerId)).toList();
    }

    private LocalDate today() {
        return Geo.stockholmDate(clock.instant());
    }

    /** The date the away rule is checked against: today for NOW rides, the ride's Stockholm date otherwise. */
    public LocalDate rideDate(RideEntity ride) {
        return ride.getKind() == RideKind.NOW ? today() : Geo.stockholmDate(ride.getScheduledAt());
    }

    private RideOfferEntity newOffer(RideEntity ride, UserEntity driver) {
        RideOfferEntity o = new RideOfferEntity();
        o.setRideId(ride.getId());
        o.setDriverId(driver.getId());
        o.setStatus(OfferStatus.OFFERED);
        o.setOfferedAt(clock.instant());
        return offers.save(o);
    }

    private void reset(RideOfferEntity o, boolean priority) {
        o.setStatus(OfferStatus.OFFERED);
        o.setPriority(priority);
        o.setOfferedAt(clock.instant());
        o.setViewedAt(null);
        o.setRespondedAt(null);
        o.setComment(null);
        offers.save(o);
    }

    private Map<Long, RideOfferEntity> byDriver(Long rideId) {
        return offers.findByRideId(rideId).stream().collect(Collectors.toMap(RideOfferEntity::getDriverId, Function.identity()));
    }

    public List<RideOfferEntity> openOffers(Long rideId) {
        return offers.findByRideId(rideId).stream().filter(o -> o.getStatus().isOpen()).toList();
    }

    public java.util.Optional<RideOfferEntity> offerOf(Long rideId, Long driverId) {
        return offers.findByRideIdAndDriverId(rideId, driverId);
    }

    /** Booking: NOW to drivers available now and not away today; SCHEDULED to everybody not away on the ride's date. */
    public void createInitialOffers(RideEntity ride) {
        LocalDate date = rideDate(ride);
        for (UserEntity d : driverPool(ride)) {
            boolean eligible = !d.isAwayOn(date) && (ride.getKind() == RideKind.SCHEDULED || d.isDriverAvailableNow());
            if (eligible) {
                newOffer(ride, d);
                push.notifyUser(d.getId(), "Ny Farfartaxi-bokning", "En ny resa väntar på svar.");
            }
        }
    }

    /** NOW ride unanswered after the re-push threshold: also offer to not-available-now drivers (never to away ones). */
    @jakarta.transaction.Transactional
    public void repushNow(RideEntity ride) {
        if (ride.getStatus() != com.farfartaxi.backend.model.RideStatus.REQUESTED) {
            return;
        }
        if (em.contains(ride)) {
            // bump the version so a concurrent accept fails its optimistic check instead of leaving an orphan OFFERED offer
            em.lock(ride, jakarta.persistence.LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        }
        LocalDate date = today();
        Map<Long, RideOfferEntity> existing = byDriver(ride.getId());
        for (UserEntity d : driverPool(ride)) {
            if (d.isAwayOn(date)) {
                continue;
            }
            RideOfferEntity o = existing.get(d.getId());
            if (o == null) {
                newOffer(ride, d);
            } else if (!o.getStatus().isOpen()) {
                continue;
            }
            push.notifyUser(d.getId(), "Resa väntar fortfarande", "Ingen har tagit resan än.");
        }
    }

    /** Pushes to every driver with an open offer, except those away on the ride's date. */
    public void pushToOpen(RideEntity ride, String title, String body) {
        LocalDate date = rideDate(ride);
        for (RideOfferEntity o : openOffers(ride.getId())) {
            boolean away = users.findById(o.getDriverId()).map(u -> u.isAwayOn(date)).orElse(true);
            if (!away) {
                push.notifyUser(o.getDriverId(), title, body);
            }
        }
    }

    /** OFFERED to VIEWED with a conditional update, so a concurrently closed offer is never resurrected. */
    @jakarta.transaction.Transactional
    public void markViewed(RideOfferEntity o) {
        if (o.getStatus() == OfferStatus.OFFERED) {
            offers.markViewedIfOffered(o.getId(), clock.instant());
            if (em.contains(o)) {
                em.refresh(o);
            }
        }
    }

    /**
     * The driver went away: open offers of rides on a date inside the new away period are withdrawn.
     * @return rides that were left without any open offer (still REQUESTED)
     */
    public List<RideEntity> withdrawForAway(UserEntity driver, java.util.function.Function<Long, RideEntity> rideLoader) {
        List<RideEntity> emptied = new ArrayList<>();
        for (RideOfferEntity o : offers.findByDriverIdAndStatusIn(driver.getId(), List.of(OfferStatus.OFFERED, OfferStatus.VIEWED))) {
            RideEntity ride = rideLoader.apply(o.getRideId());
            if (ride == null || ride.getStatus() != com.farfartaxi.backend.model.RideStatus.REQUESTED
                || !driver.isAwayOn(rideDate(ride))) {
                continue;
            }
            o.setStatus(OfferStatus.WITHDRAWN);
            o.setRespondedAt(clock.instant());
            offers.save(o);
            if (openOffers(ride.getId()).isEmpty()) {
                emptied.add(ride);
            }
        }
        return emptied;
    }

    /** Winner ACCEPTED, every other open offer WITHDRAWN. */
    public void accept(RideEntity ride, UserEntity driver) {
        Instant now = clock.instant();
        for (RideOfferEntity o : offers.findByRideId(ride.getId())) {
            if (o.getDriverId().equals(driver.getId())) {
                o.setStatus(OfferStatus.ACCEPTED);
                o.setRespondedAt(now);
                offers.save(o);
            } else if (o.getStatus().isOpen()) {
                o.setStatus(OfferStatus.WITHDRAWN);
                o.setRespondedAt(now);
                offers.save(o);
            }
        }
    }

    private Map<Long, UserEntity> driversById(RideEntity ride) {
        return driverPool(ride).stream().collect(Collectors.toMap(UserEntity::getId, Function.identity()));
    }

    /** In the ride's driver pool and not away on the date. */
    private static boolean isEligibleDriver(UserEntity d, LocalDate date) {
        return d != null && !d.isAwayOn(date);
    }

    /** @return true when no open offer is left (the ride should become NO_DRIVER) */
    public boolean decline(RideOfferEntity offer, String comment) {
        offer.setStatus(OfferStatus.DECLINED);
        offer.setRespondedAt(clock.instant());
        offer.setComment(comment == null ? null : (comment.length() > 512 ? comment.substring(0, 512) : comment));
        offers.save(offer);
        return openOffers(offer.getRideId()).isEmpty();
    }

    public void expireOpen(RideEntity ride) {
        Instant now = clock.instant();
        for (RideOfferEntity o : openOffers(ride.getId())) {
            o.setStatus(OfferStatus.EXPIRED);
            o.setRespondedAt(now);
            offers.save(o);
        }
    }

    /** Cancellation: nothing is offerable any more. */
    public void withdrawAll(RideEntity ride) {
        Instant now = clock.instant();
        for (RideOfferEntity o : offers.findByRideId(ride.getId())) {
            if (o.getStatus().isOpen() || o.getStatus() == OfferStatus.ACCEPTED) {
                o.setStatus(OfferStatus.WITHDRAWN);
                o.setRespondedAt(now);
                offers.save(o);
            }
        }
    }

    /** Driver returned the ride: their offer stays WITHDRAWN, the others are offered again, decliners stay declined. */
    public int reofferAfterReturn(RideEntity ride, Long returningDriverId) {
        int open = 0;
        LocalDate date = rideDate(ride);
        Map<Long, UserEntity> drivers = driversById(ride);
        for (RideOfferEntity o : offers.findByRideId(ride.getId())) {
            if (o.getDriverId().equals(returningDriverId)) {
                o.setStatus(OfferStatus.WITHDRAWN);
                o.setRespondedAt(clock.instant());
                offers.save(o);
            } else if (REOFFERABLE.contains(o.getStatus())) {
                if (!isEligibleDriver(drivers.get(o.getDriverId()), date)) {
                    continue;
                }
                reset(o, false);
                push.notifyUser(o.getDriverId(), "Resa blev ledig igen", "En resa är tillbaka i kön.");
                open++;
            } else if (o.getStatus().isOpen()) {
                open++;
            }
        }
        return open;
    }

    /**
     * Material edit of an accepted ride: the previous driver is offered first (priority flag, pushed first),
     * the others are offered again; decliners stay declined.
     */
    public void reofferAfterMaterialEdit(RideEntity ride, Long priorDriverId, String passengerName) {
        List<RideOfferEntity> all = new ArrayList<>(offers.findByRideId(ride.getId()));
        LocalDate date = rideDate(ride);
        Map<Long, UserEntity> drivers = driversById(ride);
        for (RideOfferEntity o : all) {
            if (o.getDriverId().equals(priorDriverId)) {
                if (isEligibleDriver(drivers.get(priorDriverId), date)) {
                    reset(o, true);
                    push.notifyUser(o.getDriverId(), "Resan ändrades", passengerName + " ändrade resan — bekräfta.");
                } else {
                    o.setStatus(OfferStatus.WITHDRAWN); // away on the new date: cannot take it
                    o.setRespondedAt(clock.instant());
                    offers.save(o);
                }
            }
        }
        for (RideOfferEntity o : all) {
            if (!o.getDriverId().equals(priorDriverId) && REOFFERABLE.contains(o.getStatus())
                && isEligibleDriver(drivers.get(o.getDriverId()), date)) {
                reset(o, false);
                push.notifyUser(o.getDriverId(), "Resa väntar", "En resa har ändrats och väntar på en förare.");
            }
        }
    }

    /**
     * The ride's date changed: drivers away on the new date lose their open offer, newly free drivers get one.
     * With {@code reopenDecliners} (NO_DRIVER edited to a new time) decliners are offered again as well.
     */
    public void syncForTimeChange(RideEntity ride, boolean reopenDecliners) {
        LocalDate date = Geo.stockholmDate(ride.getScheduledAt());
        Map<Long, RideOfferEntity> existing = byDriver(ride.getId());
        for (UserEntity d : driverPool(ride)) {
            RideOfferEntity o = existing.get(d.getId());
            if (d.isAwayOn(date)) {
                if (o != null && o.getStatus().isOpen()) {
                    o.setStatus(OfferStatus.WITHDRAWN);
                    o.setRespondedAt(clock.instant());
                    offers.save(o);
                }
            } else if (o == null) {
                newOffer(ride, d);
                push.notifyUser(d.getId(), "Ny Farfartaxi-bokning", "En ny resa väntar på svar.");
            } else if (REOFFERABLE.contains(o.getStatus()) || (reopenDecliners && o.getStatus() == OfferStatus.DECLINED)) {
                reset(o, false);
                push.notifyUser(d.getId(), "Resa väntar", "En resa har ändrats och väntar på svar.");
            } else if (o.getStatus().isOpen()) {
                push.notifyUser(d.getId(), "Resan ändrades", "Tiden för en resa har ändrats.");
            }
        }
    }

    /**
     * Drivers a keep-waiting would (re-)offer: EXPIRED offers, plus drivers never offered (booking rules: NOW needs
     * available-now). Never WITHDRAWN/DECLINED offers (e.g. the driver who returned the ride) and never away drivers.
     */
    private List<UserEntity> keepWaitingCandidates(RideEntity ride) {
        Map<Long, RideOfferEntity> existing = byDriver(ride.getId());
        LocalDate date = rideDate(ride);
        List<UserEntity> out = new ArrayList<>();
        for (UserEntity d : driverPool(ride)) {
            if (d.isAwayOn(date)) {
                continue;
            }
            RideOfferEntity o = existing.get(d.getId());
            if (o != null) {
                if (o.getStatus() == OfferStatus.EXPIRED) {
                    out.add(d);
                }
            } else if (ride.getKind() == RideKind.SCHEDULED || d.isDriverAvailableNow()) {
                out.add(d);
            }
        }
        return out;
    }

    public boolean canKeepWaiting(RideEntity ride) {
        if (ride.getKind() == RideKind.SCHEDULED && !ride.getScheduledAt().isAfter(clock.instant())) {
            return false;
        }
        return !keepWaitingCandidates(ride).isEmpty();
    }

    public void keepWaiting(RideEntity ride) {
        Map<Long, RideOfferEntity> existing = byDriver(ride.getId());
        for (UserEntity d : keepWaitingCandidates(ride)) {
            RideOfferEntity o = existing.get(d.getId());
            if (o == null) {
                newOffer(ride, d);
            } else {
                reset(o, false);
            }
            push.notifyUser(d.getId(), "Resa väntar", "En resa väntar fortfarande på en förare.");
        }
    }
}
