package dev.worldforge.reputation;

import java.util.*;

/**
 * Deterministic, bounded rumor delivery over an adapter-provided social graph.
 * This class never discovers contacts by proximity alone: the caller supplies people
 * the witness actually knows and whether they can communicate in the current world tick.
 */
public final class RumorNetwork {
    public static final int MAX_DELIVERIES_PER_PASS = 8;
    public static final int MIN_TRUSTED_RELATIONSHIP = 35;

    public record Contact(String characterId, int relationship, String organizationId,
                          boolean canCommunicate, boolean sameOrganization) {
        public Contact {
            if (characterId == null || characterId.isBlank()) throw new IllegalArgumentException("contact character id required");
            organizationId = organizationId == null ? "" : organizationId;
            relationship = Math.max(-100, Math.min(100, relationship));
        }
    }

    @FunctionalInterface
    public interface ContactProvider {
        Collection<Contact> contactsOf(String witnessCharacterId);
    }

    @FunctionalInterface
    public interface Membership {
        boolean isMember(String characterId, String organizationId);
    }

    public record Delivery(String recipientId, String scopeKey, double confidence, int depth,
                           String channel) { }

    private RumorNetwork() { }

    /**
     * Shares one known scoped fact with eligible contacts. Organization channels require
     * membership confirmation; personal channels require a trusted relationship. No world
     * broadcast, no automatic cross-settlement propagation, and no recursive same-pass chain.
     */
    public static List<Delivery> spread(ReputationStore store, String witnessId, String playerId,
                                        ReputationStore.Scope scope, String scopeId, long now,
                                        ContactProvider contacts, Membership membership) {
        Objects.requireNonNull(store, "reputation store");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(contacts, "contact provider");
        Objects.requireNonNull(membership, "membership checker");
        ReputationStore.KnownReputation known = store.known(witnessId, playerId, scope, scopeId);
        if (known == null || now < known.observedAt || known.depth >= 3 || known.confidence < 0.2)
            return List.of();

        Collection<Contact> supplied = contacts.contactsOf(witnessId);
        if (supplied == null || supplied.isEmpty()) return List.of();
        List<Contact> ordered = new ArrayList<>(supplied);
        ordered.sort(Comparator.comparing(Contact::characterId));
        List<Delivery> delivered = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String scopeKey = ReputationStore.target(scope, scopeId);

        for (Contact contact : ordered) {
            if (delivered.size() >= MAX_DELIVERIES_PER_PASS) break;
            String recipient = contact.characterId();
            if (!contact.canCommunicate() || recipient.equals(witnessId) || !seen.add(recipient)) continue;
            boolean success = false;
            String channel = "";
            if (scope == ReputationStore.Scope.ORGANIZATION && contact.sameOrganization()
                    && scopeId.equals(contact.organizationId())) {
                // A contact claiming the same organization cannot fall back to a personal route
                // when membership verification fails.
                if (!membership.isMember(recipient, contact.organizationId())) continue;
                success = store.shareWithinOrganization(witnessId, recipient, playerId,
                        contact.organizationId(), now, id -> membership.isMember(id, contact.organizationId()));
                channel = "organization";
            } else if (contact.relationship() >= MIN_TRUSTED_RELATIONSHIP) {
                success = store.shareWithTrustedContact(witnessId, recipient, playerId,
                        scope, scopeId, now, contact.relationship(), 0.8);
                channel = "personal";
            }
            if (!success) continue;
            ReputationStore.KnownReputation received = store.known(recipient, playerId, scope, scopeId);
            delivered.add(new Delivery(recipient, scopeKey, received.confidence, received.depth, channel));
        }
        return Collections.unmodifiableList(delivered);
    }
}
