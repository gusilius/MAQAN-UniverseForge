# MAQAN Dialogue & Social Intelligence Contract v1

## Identity and ownership
- CharacterRegistry remains the sole owner of persistent person identity.
- DialogueMemory is an adapter over the canonical speaker Character.props map; it must not create a parallel NPC record.
- Dialogue sessions are transient. Facts, semantic obligations and bounded interaction history are character-owned durable state.
- Player flags and player-specific memory remain player-owned.

## Semantic memory
- Facts are keyed and can be recalled in later lines with `{memory:key}`.
- Promise, request and grievance records have stable IDs, open/resolved state, target, text and due metadata.
- Records are context, not automatic fulfillment. Resolve them only after the authoritative quest, inventory, economy, relationship or work-order subsystem confirms the outcome.
- Conversation history is bounded; durable obligations and facts are not silently discarded with history pruning.

## Conversation model
- Context providers supply canonical character identity, role, house, realm, player identity, relationships and social initiative intent.
- Conditions control what an NPC knows or is willing to discuss. Effects commit domain actions through the owning subsystem.
- Existing domain bridges remain authoritative: RelationsBridge handles affinity/love, gifts, requests, hiring and family relationship actions; QuestsDialogueBridge handles offer/accept/turn-in; TradeDialogueBridge handles market information and buy/sell transactions.
- Dialogue text alone never grants items, resources, titles or completed tasks.
- Character-backed dialogue sessions are bound to the speaker position and close when the player leaves the configured radius.

## NPC-initiated interaction
- Autonomous conversation is opt-in through Character.props `social.initiative_dialogue`.
- `social.initiative_enabled=true` permits greetings; unresolved semantic promises, requests or grievances permit follow-up conversations.
- NPCs only initiate when a player is within 3 blocks, no other dialogue session is active, and the per-character/per-player cooldown (40 seconds) has elapsed.
- Dialogue context exposes `social_intent` and `social_subject`; the selected dialogue script must branch on these values and use the domain bridges to commit outcomes.
- This scheduler does not invent dialogue content or autonomously move an NPC toward a player.

## Shared NPC intelligence and world simulation
- The same Character state feeds residents, travellers and soldiers. Domain adapters expose legal actions and return SUCCESS, BLOCKED or RETRY.
- LifeSimulation tries at most three ranked actions per decision. A blocked action records the adapter's reason, receives a temporary cooldown, and causes a bounded alternative to be attempted. Need and skill effects apply only after SUCCESS.
- Profession, duty, class, race, rarity and level are orthogonal dimensions.
- Equipment is character-owned inventory; Minecraft equipment is a projection. Crafting consumes authoritative inventory/settlement stock.
- Military supply is logistics demand against economy stock. Morale and discipline read needs, personality, leadership, supply and recent outcomes.
- Settlement, army, realm and personal-life simulation publish events into one world event stream; each domain remains authoritative for its commits.
- Remote simulation must not claim physical navigation, animation or block placement unless the runtime executor performed it.

## Current implementation boundary
- Implemented in this pass: owner-backed semantic dialogue memory, fact placeholders, bounded event history, promise/request/grievance records, open/resolved conditions, relationship score adapter for Character social state, opt-in nearby NPC-initiated follow-ups, speaker-distance enforcement, and bounded action fallback with failure reasons.
- Still requires domain scripts and adapters to author meaningful conversation branches, automatically resolve obligations from actual deliveries/transactions, and provide initiative dialogue IDs per role/character.
- This contract does not claim that all six MAQAN domains or full universal autonomous social behavior are complete.
