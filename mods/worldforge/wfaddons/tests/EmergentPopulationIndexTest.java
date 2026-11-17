package dev.worldforge.tests;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.worldsim.EmergentPopulationIndex;
public final class EmergentPopulationIndexTest {
 public static void main(String[] args) {
  CharacterRegistry registry=new CharacterRegistry();
  Character first=registry.create("A","House A","culture-a","m",100); first.props.put("emergence.band_id","band-a");
  Character second=registry.create("B","House B","culture-a","f",102); second.props.put("emergence.band_id","band-a");
  Character other=registry.create("C","House C","culture-b","m",99); other.props.put("emergence.band_id","band-b");
  Character deceased=registry.create("D","House D","culture-a","f",80); deceased.props.put("emergence.band_id","band-a"); deceased.alive=false;
  EmergentPopulationIndex index=new EmergentPopulationIndex(registry.all());
  check(index.livingResidents("band-a").size()==2,"living residents grouped by band");
  check(index.livingResidents("band-b").size()==1,"different bands isolated");
  check(index.byGlobalId(first.globalId)==first,"UUID resolves canonical Character");
  check(index.emergentResidents().size()==4,"deceased retained in emergence history");
  Character born=registry.create("E","House A","culture-a","m",120); born.props.put("emergence.band_id","band-a"); index.add(born);
  check(index.livingResidents("band-a").size()==3,"new resident joins band index");
  check(index.byGlobalId(born.globalId)==born,"new UUID indexed in current pass");
  check(index.emergentResidents().size()==5,"new resident joins emergence history");
  System.out.println("PASS: EmergentPopulationIndexTest");
 }
 private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
