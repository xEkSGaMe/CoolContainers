# CoolContainers API — выдача ключей из другого плагина

Начиная с 2.0.0 выдача ключей доступна через `ru.coolcontainers.api.CoolContainersAPI`.
Пользоваться этим лучше, чем дёргать `/caseadmin givekey` через `dispatchCommand`:
команда не возвращает результат, не показывает, кто выдал ключи, и её нельзя вызывать из async.

## 1. Зависимость в другом плагине

### Paper `paper-plugin.yml` (рекомендуется)

```yaml
dependencies:
  server:
    CoolContainers:
      load: BEFORE
      required: true
      join-classpath: true   # чтобы были видны классы ru.coolcontainers.api.*
```

### Классический `plugin.yml`

```yaml
depend: [CoolContainers]
```

Если плагин должен работать и без CoolContainers, используй `softdepend`, **но не трогай
типы `ru.coolcontainers.*` до проверки** `Bukkit.getPluginManager().getPlugin("CoolContainers") != null`,
иначе будет `NoClassDefFoundError`. Для простой интеграции проще `required: true`.

## 2. Зависимость для компиляции

Артефакт не опубликован в публичный репозиторий, поэтому установи jar локально:

```powershell
mvn install:install-file `
  -Dfile="C:\path\to\CoolContainers-2.0.0.jar" `
  -DgroupId=ru.coolcontainers `
  -DartifactId=coolcontainers `
  -Dversion=2.0.0 `
  -Dpackaging=jar
```

Maven:

```xml
<dependency>
    <groupId>ru.coolcontainers</groupId>
    <artifactId>coolcontainers</artifactId>
    <version>2.0.0</version>
    <scope>provided</scope>
</dependency>
```

Gradle:

```gradle
repositories { mavenLocal() }
dependencies { compileOnly 'ru.coolcontainers:coolcontainers:2.0.0' }
```

## 3. Минимальный пример

```java
import org.bukkit.Bukkit;
import ru.coolcontainers.api.CoolContainersAPI;

import java.util.UUID;

public void grantKeys(UUID player, UUID donor) {
    CoolContainersAPI api = CoolContainersAPI.get();
    if (api == null) {
        getLogger().warning("CoolContainers не включён — ключи не выданы");
        return;
    }

    api.giveKeys(player, "RUBY", 3, getName(), donor)
       .whenComplete((result, ex) -> {
           // callback приходит с DB-потока — Bukkit API трогаем только на main
           getServer().getScheduler().runTask(this, () -> {
               if (ex != null || result == null || !result.success()) {
                   getLogger().warning("Не удалось выдать ключи: "
                           + (result == null ? ex : result.failure()));
                   return;
               }
               org.bukkit.entity.Player online = Bukkit.getPlayer(player);
               if (online != null) {
                   online.sendMessage("Ключи RUBY выданы. Всего: " + result.newAmount());
               }
           });
       });
}
```

## 4. Что можно и что нельзя

- `caseId` — регистр не важен (`"RUBY"`, `"ruby"` — одно и то же). При желании проверь `api.hasCase("RUBY")`.
- `amount` должен быть `> 0`. При `<= 0` вернётся `Failure.INVALID_AMOUNT`, транзакции не будет.
- Игрок может быть **оффлайн** — нужен только `UUID`.
- `sourcePlugin` — имя твоего плагина (`getName()`), попадает в `cc_admin_logs`.
- `sourcePlayer` — UUID донатера/инициатора или `null` для системной выдачи. Попадает в колонку `admin` таблицы `cc_key_transactions`.
- Вызов можно делать из любого потока: SQL уходит в пул CoolContainers, `CaseKeyChangeEvent` фаерится на main — кэш и голограммы обновятся сами.
- Не вызывай API до `onEnable()` CoolContainers: `CoolContainersAPI.get()` вернёт `null`, если плагин ещё/уже не включён.
- `getKeys(...)` возвращает `failed future` с `IllegalStateException`, если CoolContainers выключился.

## 5. Возможные ошибки

`KeyGrantResult.failure()` может быть:

| Значение | Причина |
|---|---|
| `PLUGIN_DISABLED` | CoolContainers выключился между получением API и вызовом |
| `UNKNOWN_CASE` | кейс с таким id не загружен |
| `INVALID_AMOUNT` | `amount <= 0` |
| `DB_ERROR` | ошибка записи в БД, ключи **не** начислены |

## 6. Что происходит внутри

- Ключ и запись в `cc_key_transactions` пишутся **в одной транзакции** (`KeyService.addKeys`).
- Причина транзакции — `KeyReason.PLUGIN_GIVE`. Новый enum не требует миграции: колонка `reason` — `VARCHAR(32)`.
- Отдельно пишется аудит `cc_admin_logs` с action `api.givekeys` и именем плагина. Если аудит упал — ключи остаются выданными, в лог сервера пишется warning.
- После успешного commit фаерится `CaseKeyChangeEvent`, который подхватывает `KeyCache`.
