# ForgeAIMCP

Клиентский мост для Minecraft 1.7.10: предметы, рецепты NEI, применения и ордикты.
Нужны Forge, Forgelin, NotEnoughItems и его зависимости.

Войдите в мир и выполните `/forgemcp start`. Остановка: `/forgemcp stop`, состояние: `/forgemcp status`.
Автостарт выключен: `B:autoStart=false` в `config/forgeaimcp.cfg`.
Порт по умолчанию 25576, подключение только с этого компьютера. При выходе из мира мост останавливается.

Мост принимает строку JSON с `tool` и `arguments` и возвращает `result` либо `error`.
Инструменты: get_status, search_items, get_item, get_recipes, get_usages, search_oredict, get_oredict.
Запросы выполняются на клиентском потоке. Списки поддерживают offset и limit (максимум 100).
Ошибки обработчиков NEI возвращаются в warnings с complete=false.

Сборка: `.\gradlew.bat build`.
