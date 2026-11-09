package dev.worldforge.people;

import java.util.*;

/**
 * One persistent item ledger owned by the canonical Character.
 * Minecraft entities are projections of this inventory, never its owner.
 */
public final class CharacterInventory {
    private static final String KEY = "inventory.items";
    private CharacterInventory() {}

    public record Item(String id,int count,String data) {
        public Item {
            id=validId(id);
            if(count<1||count>1_000_000)throw new IllegalArgumentException("invalid item count");
            data=data==null?"":data;
            if(data.length()>32_768)throw new IllegalArgumentException("item data too large");
        }
    }

    public static List<Item> items(Character person) {
        Objects.requireNonNull(person);
        Object raw=person.props.get(KEY);
        if(!(raw instanceof List<?> list))return List.of();
        List<Item> result=new ArrayList<>();
        for(Object entry:list) {
            if(!(entry instanceof Map<?,?> map))continue;
            Object id=map.get("id"),count=map.get("count"),data=map.get("data");
            if(id instanceof String s&&count instanceof Number n) {
                try { result.add(new Item(s,n.intValue(),data instanceof String d?d:"")); }
                catch(IllegalArgumentException ignored) { }
            }
        }
        return Collections.unmodifiableList(result);
    }

    public static int count(Character person,String itemId) {
        String id=validId(itemId);
        int total=0;
        for(Item item:items(person))if(item.id().equals(id))total=Math.addExact(total,item.count());
        return total;
    }

    public static void add(Character person,String itemId,int amount) {
        add(person,itemId,amount,"");
    }

    /** Items with different NBT payloads remain separate stacks. */
    public static void add(Character person,String itemId,int amount,String data) {
        Objects.requireNonNull(person);
        Item added=new Item(itemId,amount,data);
        List<Item> next=new ArrayList<>(items(person));
        for(int i=0;i<next.size();i++) {
            Item old=next.get(i);
            if(old.id().equals(added.id())&&old.data().equals(added.data())) {
                next.set(i,new Item(old.id(),Math.addExact(old.count(),amount),old.data()));
                write(person,next);return;
            }
        }
        next.add(added);
        next.sort(Comparator.comparing(Item::id).thenComparing(Item::data));
        write(person,next);
    }

    /** Removes exactly amount or leaves the inventory untouched. */
    public static boolean remove(Character person,String itemId,int amount) {
        Objects.requireNonNull(person);
        String id=validId(itemId);
        if(amount<1)return false;
        if(count(person,id)<amount)return false;
        int remaining=amount;
        List<Item> next=new ArrayList<>();
        for(Item item:items(person)) {
            if(!item.id().equals(id)){next.add(item);continue;}
            int take=Math.min(remaining,item.count());
            remaining-=take;
            if(item.count()>take)next.add(new Item(item.id(),item.count()-take,item.data()));
        }
        write(person,next);
        return true;
    }

    /** Atomic logical transfer: source is changed only after destination accepts the item. */
    public static boolean transfer(Character from,Character to,String itemId,int amount) {
        Objects.requireNonNull(from);Objects.requireNonNull(to);
        if(from==to||amount<1||count(from,itemId)<amount)return false;
        List<Item> payload=new ArrayList<>();
        int remaining=amount;
        for(Item item:items(from))if(item.id().equals(validId(itemId))&&remaining>0) {
            int take=Math.min(remaining,item.count());
            payload.add(new Item(item.id(),take,item.data()));
            remaining-=take;
        }
        if(remaining!=0)return false;
        for(Item item:payload)add(to,item.id(),item.count(),item.data());
        if(!remove(from,itemId,amount))throw new IllegalStateException("inventory changed during transfer");
        return true;
    }

    /**
     * Settles a deceased person's estate. Close family receives the inventory; if no eligible
     * heir exists, the estate remains durably attached to the deceased Character and its home.
     */
    public static String settleEstate(CharacterRegistry registry,Character deceased) {
        Objects.requireNonNull(registry);Objects.requireNonNull(deceased);
        if(Boolean.TRUE.equals(deceased.props.get("estate.settled")))return
                String.valueOf(deceased.props.getOrDefault("estate.recipient",""));
        Character heir=findHeir(registry,deceased);
        String home=String.valueOf(deceased.props.getOrDefault("population.location",
                deceased.props.getOrDefault("population.home","")));
        if(heir!=null) {
            transferAll(deceased,heir);
            deceased.props.put("estate.recipient",heir.id);
            deceased.props.put("estate.status","inherited");
        } else {
            // Keep every item on the persistent dead Character; it is a recoverable home estate,
            // not a dropped entity stack that can despawn or be destroyed by chunk unload.
            deceased.props.put("estate.status","awaiting_kin_or_home_claim");
            deceased.props.put("estate.home",home);
            deceased.props.put("estate.recipient","");
        }
        deceased.props.put("estate.settled",true);
        return heir==null?"":heir.id;
    }

    /** A later-discovered descendant or household member can claim a pending estate exactly once. */
    public static boolean claimEstate(Character deceased,Character claimant) {
        Objects.requireNonNull(deceased);Objects.requireNonNull(claimant);
        if(deceased.alive||!claimant.alive||!isKin(deceased,claimant)
                ||!Boolean.TRUE.equals(deceased.props.get("estate.settled"))
                ||!"awaiting_kin_or_home_claim".equals(deceased.props.get("estate.status")))return false;
        transferAll(deceased,claimant);
        deceased.props.put("estate.recipient",claimant.id);
        deceased.props.put("estate.status","inherited");
        return true;
    }

    private static void transferAll(Character from,Character to) {
        for(Item item:new ArrayList<>(items(from)))
            if(!transfer(from,to,item.id(),item.count()))
                throw new IllegalStateException("could not settle estate item "+item.id());
    }

    private static Character findHeir(CharacterRegistry registry,Character deceased) {
        if(!deceased.spouse.isBlank()) {
            Character spouse=registry.get(deceased.spouse);
            if(spouse!=null&&spouse.alive)return spouse;
        }
        // Prefer living descendants, oldest first; then parents and siblings/other descendants.
        List<Character> descendants=new ArrayList<>();
        for(String id:deceased.children){Character child=registry.get(id);if(child!=null&&child.alive)descendants.add(child);}
        descendants.sort(Comparator.comparingInt((Character c)->c.birthYear).thenComparing(c->c.id));
        if(!descendants.isEmpty())return descendants.get(0);
        for(String id:List.of(deceased.father,deceased.mother))if(!id.isBlank()){
            Character parent=registry.get(id);if(parent!=null&&parent.alive)return parent;
        }
        for(Character candidate:registry.all())
            if(candidate.alive&&candidate!=deceased&&isKin(deceased,candidate))return candidate;
        return null;
    }

    private static boolean isKin(Character a,Character b) {
        if(a.id.equals(b.spouse)||b.id.equals(a.spouse))return true;
        if(a.children.contains(b.id)||b.children.contains(a.id))return true;
        if(!a.father.isBlank()&&(a.father.equals(b.id)||a.father.equals(b.father)||a.father.equals(b.mother)))return true;
        if(!a.mother.isBlank()&&(a.mother.equals(b.id)||a.mother.equals(b.father)||a.mother.equals(b.mother)))return true;
        return !b.father.isBlank()&&(b.father.equals(a.id)||b.children.contains(a.id))
                ||!b.mother.isBlank()&&(b.mother.equals(a.id)||b.children.contains(a.id));
    }

    private static void write(Character person,List<Item> items) {
        List<Map<String,Object>> encoded=new ArrayList<>();
        for(Item item:items) {
            Map<String,Object> entry=new LinkedHashMap<>();
            entry.put("id",item.id());entry.put("count",item.count());entry.put("data",item.data());
            encoded.add(entry);
        }
        person.props.put(KEY,encoded);
    }

    private static String validId(String id) {
        if(id==null||id.isBlank()||id.length()>160||!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            throw new IllegalArgumentException("invalid item id");
        return id;
    }
}
