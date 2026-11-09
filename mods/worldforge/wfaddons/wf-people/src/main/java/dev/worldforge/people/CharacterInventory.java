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
