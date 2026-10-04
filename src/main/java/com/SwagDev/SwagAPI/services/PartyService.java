package com.SwagDev.SwagAPI.services;

import com.SwagDev.SwagAPI.api.IPartyService;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link IPartyService} implementation. See the interface javadoc for the shared
 * "one player, one party, ecosystem-wide" and "no persistence" contracts this honors.
 */
public class PartyService implements IPartyService {

    /** Mutable internal party record. Never handed out directly — {@link #getParty} wraps it in an immutable {@link View}. */
    private static final class PartyImpl {
        final UUID id = UUID.randomUUID();
        final LinkedHashSet<UUID> members = new LinkedHashSet<>();
        UUID leaderId;
        final int maxSize;

        PartyImpl(UUID leaderId, int maxSize) {
            this.leaderId = leaderId;
            this.maxSize = maxSize;
            this.members.add(leaderId);
        }
    }

    private record View(UUID id, UUID leaderId, List<UUID> members, int maxSize) implements PartyView {
        @Override
        public UUID getId() { return id; }

        @Override
        public UUID getLeaderId() { return leaderId; }

        @Override
        public List<UUID> getMembers() { return members; }

        @Override
        public int size() { return members.size(); }

        @Override
        public int getMaxSize() { return maxSize; }

        @Override
        public boolean isLeader(UUID playerId) { return leaderId.equals(playerId); }

        @Override
        public boolean isMember(UUID playerId) { return members.contains(playerId); }
    }

    private record PendingInvite(UUID partyId, UUID inviterId) {
    }

    private final Map<UUID, PartyImpl> partiesById = new ConcurrentHashMap<>();
    private final Map<UUID, PartyImpl> partyByMember = new ConcurrentHashMap<>();
    private final Map<UUID, PendingInvite> pendingInvitesByInvitee = new ConcurrentHashMap<>();

    private static View viewOf(PartyImpl party) {
        return new View(party.id, party.leaderId, List.copyOf(party.members), party.maxSize);
    }

    @Override
    public CreateResult createParty(UUID leaderId, int maxSize) {
        if (partyByMember.containsKey(leaderId)) {
            return CreateResult.ALREADY_IN_PARTY;
        }
        PartyImpl party = new PartyImpl(leaderId, maxSize);
        partiesById.put(party.id, party);
        partyByMember.put(leaderId, party);
        return CreateResult.SUCCESS;
    }

    @Override
    public PartyView getParty(UUID playerId) {
        PartyImpl party = partyByMember.get(playerId);
        return party == null ? null : viewOf(party);
    }

    @Override
    public boolean isInParty(UUID playerId) {
        return partyByMember.containsKey(playerId);
    }

    @Override
    public InviteResult invite(UUID leaderId, UUID targetId) {
        if (leaderId.equals(targetId)) {
            return InviteResult.SELF_INVITE;
        }
        PartyImpl party = partyByMember.get(leaderId);
        if (party == null) {
            return InviteResult.NOT_IN_PARTY;
        }
        if (!party.leaderId.equals(leaderId)) {
            return InviteResult.NOT_LEADER;
        }
        if (party.members.contains(targetId)) {
            return InviteResult.ALREADY_MEMBER;
        }
        if (partyByMember.containsKey(targetId)) {
            return InviteResult.TARGET_ALREADY_IN_PARTY;
        }
        if (party.members.size() >= party.maxSize) {
            return InviteResult.PARTY_FULL;
        }
        pendingInvitesByInvitee.put(targetId, new PendingInvite(party.id, leaderId));
        return InviteResult.SUCCESS;
    }

    @Override
    public UUID getPendingInviter(UUID inviteeId) {
        PendingInvite pending = pendingInvitesByInvitee.get(inviteeId);
        return pending == null ? null : pending.inviterId();
    }

    @Override
    public boolean hasPendingInvite(UUID inviteeId) {
        return pendingInvitesByInvitee.containsKey(inviteeId);
    }

    @Override
    public boolean hasPendingInviteFrom(UUID inviteeId, UUID inviterId) {
        PendingInvite pending = pendingInvitesByInvitee.get(inviteeId);
        return pending != null && pending.inviterId().equals(inviterId);
    }

    @Override
    public void cancelInvite(UUID inviteeId) {
        pendingInvitesByInvitee.remove(inviteeId);
    }

    @Override
    public RespondResult accept(UUID inviteeId) {
        PendingInvite pending = pendingInvitesByInvitee.get(inviteeId);
        if (pending == null) {
            return RespondResult.NO_PENDING_INVITE;
        }
        PartyImpl party = partiesById.get(pending.partyId());
        // The party may have disbanded, or the invitee may have joined a different party via
        // another invite, between invite() and accept() — both leave this invite un-actionable.
        if (party == null || partyByMember.containsKey(inviteeId)) {
            pendingInvitesByInvitee.remove(inviteeId, pending);
            return RespondResult.NO_PENDING_INVITE;
        }
        if (party.members.size() >= party.maxSize) {
            pendingInvitesByInvitee.remove(inviteeId, pending);
            return RespondResult.PARTY_FULL;
        }
        party.members.add(inviteeId);
        partyByMember.put(inviteeId, party);
        pendingInvitesByInvitee.remove(inviteeId, pending);
        return RespondResult.ACCEPTED;
    }

    @Override
    public RespondResult decline(UUID inviteeId) {
        PendingInvite pending = pendingInvitesByInvitee.remove(inviteeId);
        return pending == null ? RespondResult.NO_PENDING_INVITE : RespondResult.DECLINED;
    }

    @Override
    public LeaveResult leave(UUID playerId) {
        PartyImpl party = partyByMember.remove(playerId);
        if (party == null) {
            return LeaveResult.NOT_IN_PARTY;
        }
        party.members.remove(playerId);

        if (party.members.isEmpty()) {
            partiesById.remove(party.id);
            return LeaveResult.DISBANDED;
        }

        if (party.leaderId.equals(playerId)) {
            // LinkedHashSet iteration order is join order — the first remaining entry is the
            // longest-standing member left, exactly who should be promoted.
            party.leaderId = party.members.iterator().next();
        }
        return LeaveResult.LEFT;
    }

    @Override
    public KickResult kick(UUID leaderId, UUID targetId) {
        if (leaderId.equals(targetId)) {
            return KickResult.CANNOT_KICK_SELF;
        }
        PartyImpl party = partyByMember.get(leaderId);
        if (party == null) {
            return KickResult.NOT_IN_PARTY;
        }
        if (!party.leaderId.equals(leaderId)) {
            return KickResult.NOT_LEADER;
        }
        if (!party.members.contains(targetId)) {
            return KickResult.TARGET_NOT_IN_PARTY;
        }
        party.members.remove(targetId);
        partyByMember.remove(targetId);
        return KickResult.SUCCESS;
    }

    @Override
    public DisbandResult disband(UUID leaderId) {
        PartyImpl party = partyByMember.get(leaderId);
        if (party == null) {
            return DisbandResult.NOT_IN_PARTY;
        }
        if (!party.leaderId.equals(leaderId)) {
            return DisbandResult.NOT_LEADER;
        }
        for (UUID memberId : party.members) {
            partyByMember.remove(memberId);
        }
        partiesById.remove(party.id);
        return DisbandResult.SUCCESS;
    }

    @Override
    public PromoteResult promote(UUID promoterId, UUID targetId) {
        PartyImpl party = partyByMember.get(promoterId);
        if (party == null) {
            return PromoteResult.NOT_IN_PARTY;
        }
        if (!party.leaderId.equals(promoterId)) {
            return PromoteResult.NOT_LEADER;
        }
        if (promoterId.equals(targetId)) {
            return PromoteResult.ALREADY_LEADER;
        }
        if (!party.members.contains(targetId)) {
            return PromoteResult.TARGET_NOT_MEMBER;
        }
        party.leaderId = targetId;
        return PromoteResult.SUCCESS;
    }

    /** Clears all in-memory state. Called from {@code SwagAPI#onDisable}, matching every other in-memory manager's shutdown precedent in this ecosystem. */
    public void shutdown() {
        partiesById.clear();
        partyByMember.clear();
        pendingInvitesByInvitee.clear();
    }
}
