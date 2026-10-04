package com.SwagDev.SwagAPI.api;

import java.util.List;
import java.util.UUID;

/**
 * Shared, domain-agnostic in-memory party service — pure "group of players with a leader"
 * plumbing, with no persistence and no feature-specific concepts (no fishing expeditions, no
 * dungeon instances, etc). Consumers own every bit of domain logic (proximity bonuses, instance
 * party-size caps, messaging/UX) themselves and call this service only for the shared mechanics:
 * create/invite/accept/decline/leave/kick/disband/promote and lookups.
 *
 * <p><b>In-memory only.</b> Exactly like the two independent implementations this unifies
 * (SwagFishing's old {@code PartyManager}/{@code FishingParty}, SwagDungeons' old
 * {@code party.PartyManager}/{@code party.Party}) — every party and pending invite is lost on
 * server restart/reload. There is no database table and there never will be one; if that ever
 * changes it belongs in a new interface, not bolted onto this one.</p>
 *
 * <p><b>One player, one party, ecosystem-wide.</b> Membership is keyed purely by player UUID with
 * no per-plugin/domain namespacing — a player who is in a SwagFishing party is, as far as this
 * service is concerned, in the exact same party if SwagDungeons (or any other consumer) looks them
 * up. This is a deliberate consequence of unifying two previously-separate in-memory maps into one
 * shared source of truth: a "party" is now one cross-plugin concept, not a per-feature one. Callers
 * that need feature-specific data alongside a shared party (e.g. an active fishing expedition, or a
 * dungeon instance) must key that data by the party's {@link PartyView#getId()} (or by player UUID)
 * in their own storage — never ask this service to hold it.</p>
 *
 * <p><b>Messaging is not this service's job.</b> Every method here is a pure state mutation that
 * returns an outcome enum (or throws nothing) — it never sends a player a message. Every consumer
 * is expected to translate the returned result into whatever chat/GUI feedback matches its own UX,
 * exactly as SwagFishing and SwagDungeons already did before this service existed.</p>
 *
 * <p><b>Invite expiry is not this service's job either.</b> A pending invite is stored with no
 * built-in timeout — this service will hold it until the caller explicitly {@link
 * #cancelInvite(UUID)}s it (or it's consumed via {@link #accept}/{@link #decline}). Callers that
 * want invites to expire schedule their own timer (a one-shot delayed task per invite, a periodic
 * sweep — whatever already matches their existing pattern) and call {@link #cancelInvite(UUID)}
 * when it fires, after checking {@link #hasPendingInviteFrom(UUID, UUID)} if they need to confirm
 * the invite they scheduled the timer for is still the one outstanding.</p>
 */
public interface IPartyService {

    enum CreateResult {SUCCESS, ALREADY_IN_PARTY}

    enum InviteResult {SUCCESS, NOT_IN_PARTY, NOT_LEADER, PARTY_FULL, SELF_INVITE, ALREADY_MEMBER, TARGET_ALREADY_IN_PARTY}

    enum RespondResult {ACCEPTED, DECLINED, NO_PENDING_INVITE, PARTY_FULL}

    enum LeaveResult {LEFT, DISBANDED, NOT_IN_PARTY}

    enum KickResult {SUCCESS, NOT_IN_PARTY, NOT_LEADER, TARGET_NOT_IN_PARTY, CANNOT_KICK_SELF}

    enum DisbandResult {SUCCESS, NOT_IN_PARTY, NOT_LEADER}

    enum PromoteResult {SUCCESS, NOT_IN_PARTY, NOT_LEADER, ALREADY_LEADER, TARGET_NOT_MEMBER}

    /** Read-only snapshot of a party's state at query time — mutating a party never mutates a view already handed out. */
    interface PartyView {

        UUID getId();

        UUID getLeaderId();

        /** Members in join order (the leader is whichever id {@link #getLeaderId()} names, not necessarily index 0 after a promotion). */
        List<UUID> getMembers();

        int size();

        /** The cap passed to {@link IPartyService#createParty(UUID, int)} when this party was created. */
        int getMaxSize();

        boolean isLeader(UUID playerId);

        boolean isMember(UUID playerId);
    }

    /**
     * Creates a new party with {@code leaderId} as its sole member/leader.
     *
     * @param maxSize the party size cap enforced by {@link #invite} and {@link #accept} for this
     *                party's whole lifetime. Callers with no cap of their own should pass {@link
     *                Integer#MAX_VALUE}.
     */
    CreateResult createParty(UUID leaderId, int maxSize);

    /** The party {@code playerId} belongs to, or {@code null} if they aren't in one. */
    PartyView getParty(UUID playerId);

    boolean isInParty(UUID playerId);

    /** Sends an invite from {@code leaderId}'s party to {@code targetId}. Replaces any invite {@code targetId} already had pending, from any inviter. */
    InviteResult invite(UUID leaderId, UUID targetId);

    /** Who (if anyone) currently has an invite pending out to {@code inviteeId}. Null if none. */
    UUID getPendingInviter(UUID inviteeId);

    boolean hasPendingInvite(UUID inviteeId);

    /** True if {@code inviteeId} has a pending invite AND it was sent by {@code inviterId} specifically. */
    boolean hasPendingInviteFrom(UUID inviteeId, UUID inviterId);

    /** Removes {@code inviteeId}'s pending invite unconditionally, if any. No-op if there isn't one. Intended for callers' own expiry timers. */
    void cancelInvite(UUID inviteeId);

    RespondResult accept(UUID inviteeId);

    RespondResult decline(UUID inviteeId);

    /** Removes {@code playerId} from their party. If they were the leader and members remain, the longest-standing remaining member (join order) is promoted. */
    LeaveResult leave(UUID playerId);

    KickResult kick(UUID leaderId, UUID targetId);

    DisbandResult disband(UUID leaderId);

    /** Transfers leadership of {@code promoterId}'s party to {@code targetId}, an existing member. */
    PromoteResult promote(UUID promoterId, UUID targetId);
}
