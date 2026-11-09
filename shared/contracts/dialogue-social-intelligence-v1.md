# MAQAN Dialogue & Social Intelligence Contract v1

## Identity and ownership
- CharacterRegistry remains the sole owner of persistent person identity.
- DialogueMemory is an adapter over a caller-owned map. NPC conversations must inject the speaker Character.props; the dialogue addon must not create a parallel NPC record.
- Dialogue sessions are transient. Facts, promises, relationship scores and bounded event history are durable character state.
- Player flags and player-specific memory remain player-owned.

## Conversation model
A conversation is contextual exchange, not a linear menu. Context providers supply identity, relationship, duty, faction, recent events, active promises, known facts and available actions. Conditions control what an NPC knows or discusses; effects commit domain actions through the owning subsystem.
Memory supports durable facts (help, debts, secrets, betrayals, promises and discoveries) and bounded ordered history for continuity. History is not a substitute for quest, relationship or inventory state.

## Social behavior
- Numeric relationship score is canonical; friend/rival/enemy labels are derived.
- Personality and culture influence tone, trust, willingness, conflict escalation and negotiation.
- Requests, promises, trades, recruitment, work orders and quests commit through their authoritative systems. Dialogue text alone never grants items, resources, titles or completed tasks.
- NPC-initiated conversations are scheduled intents with cooldowns and interruption rules, not unsolicited client popups.

## Shared NPC intelligence and world simulation
- The same Character state feeds residents, travellers and soldiers. Domain adapters expose legal actions and return SUCCESS, BLOCKED or RETRY with a reason.
- On failure, the planner records cause, temporarily excludes the failed action, searches a bounded alternative and applies effects only after authoritative SUCCESS.
- Profession, duty, class, race, rarity and level are orthogonal dimensions.
- Equipment is character-owned inventory; Minecraft equipment is a projection. Crafting consumes authoritative inventory/settlement stock.
- Military supply is logistics demand against shared economy stock. Morale and discipline read needs, personality, leadership, supply and recent outcomes.
- Settlement, army, realm and personal-life simulation publish events into one world event stream; each domain remains authoritative for its commits.
- Remote simulation must not claim physical navigation, animation or block placement unless the runtime executor performed it.

## Validation boundary
DialogueMemory provides bounded memory primitives, but autonomous conversations, full memory retrieval, negotiation, universal NPC executors and all cross-system event reactions are not thereby complete.