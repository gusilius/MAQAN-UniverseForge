# wf-war

Своя система войн и дипломатии поверх политической карты ядра (не HYW).

- Повод войны - JSON `data/<ns>/wf_war/*.json`: цель `conquer` / `subjugate` / `independence` / `humiliate`, правило цели, перемирие.
- Очки войны: сражения (`battle`), занятие титулов (`occupy`), время. Мир: белый (по согласию), на условиях победителя (хватает очков), капитуляция.
- Подданные и союзники сторон вступают автоматически. Итог меняет карту (`give`, `setOverlord`), ставит перемирие.
- На карту выводятся `enemies`, `allies` (слой «Дипломатия») и `war.state` (отдельный слой).
- Действия карты: `war_declare`, `war_independence`, `war_peace_white`, `war_peace_demand`, `war_peace_accept`, `war_enforce`, `war_surrender`, `ally`, `ally_break`.
- Движок хранит своё состояние отдельно: `toJson` / `fromJson`.
