# Предпросмотр окна карты без Minecraft

Заглушки классов Minecraft рисуют `MapScreen` в PNG, чтобы проверять раскладку интерфейса без запуска игры.
Это только проверка расположения: шрифт и размеры текста в игре будут немного другими.

```
cd src/main/java
javac -proc:none -encoding UTF-8 -d /tmp/out -sourcepath ../../../tools/uipreview/stubs:. \
  $(find ../../../tools/uipreview/stubs -name '*.java') ../../../tools/uipreview/dev/worldforge/mod/client/Shot.java \
  dev/worldforge/mod/client/MapScreen.java
java -cp /tmp/out dev.worldforge.mod.client.Shot <datapack.zip> planets:game_of_thrones realms shot.png 960 540
```
