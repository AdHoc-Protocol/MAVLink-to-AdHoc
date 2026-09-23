# MAVLink-to-AdHoc — MAVLink dialect XML → AdHoc protocol description

> One of the [**converters to AdHoc protocol**](https://github.com/AdHoc-Protocol#converters-to-adhoc-protocol).
> Take a protocol you already have, get an [AdHoc](https://github.com/AdHoc-Protocol/AdHoc-protocol) description,
> open it in the Observer. The result is a starting point you refine by hand, not a finished protocol.

Translates [MAVLink](https://mavlink.io/) message-definition XML files into [AdHoc](https://github.com/AdHoc-Protocol)
protocol-description `.cs` files. The intent is to demonstrate the AdHoc data-modelling capabilities on a
non-trivial real-world protocol (the standard drone telemetry / command format used by ArduPilot, PX4,
QGroundControl, and many other open-source flight-control stacks).

The converter is a single Java class. It loads each dialect XML into a DOM (recursively resolving `<include>`),
builds a small in-memory model (messages, fields, enums, entries, params, deprecation notes) and emits one
self-contained `.cs` per dialect — ready to be fed to AdHocAgent for code generation.

Every generated descriptor has been validated with AdHocAgent's local parse-only mode (see [Validating](#validating-the-output)).

## Layout

| Path                                 | Contents                                                                  |
|:-------------------------------------|:--------------------------------------------------------------------------|
| `msgs/*.xml`                         | 19 MAVLink dialect XMLs synced from upstream `master`                     |
| `src/org/unirail/MavLink2AdHoc.java` | The converter (single class, DOM-based, Java 17+)                         |
| `AdHoc/*.cs`                         | Generated descriptors, one per dialect (re-created by running the converter) |
| `out/`                               | IntelliJ build output (ephemeral)                                         |

## What gets generated per dialect

For each `<dialect>.xml`, the converter writes `<dialect>.cs` (`namespace org.mavlink`, `interface <dialect>`)
containing, in order:

1. **Packs Inventory (Dashboard)** — an alphabetised `<see cref='Pack'/>` menu of every message, deliberately
   **without** `id` attributes. A pack id is AdHoc's own internal matter and AdHocAgent assigns it. The canonical
   MAVLink message number is source metadata and lives inside the pack as `public const int mavlink_message_id`,
   so a migration stays auditable and a gateway can map both ways. Pinning it into the Dashboard would claim a
   wire compatibility that does not exist: AdHoc frames its own packets, so a descriptor generated from MAVLink
   is a different protocol carrying the same data.
2. **Pack classes** — every `<message>` becomes `class <NAME> { … }`, grouped under a banner comment naming the XML
   the message comes from (included dialects first). Field types follow the table below. MAVLink field metadata is
   carried as **AdHoc custom attributes** (see [Field metadata](#field-metadata-as-custom-attributes)).
3. **Enums** — every `<enum>` becomes `enum <NAME> { … }`. `bitmask="true"` becomes `[Flags]`; values that do not
   fit a 32-bit `int` widen the enum to `: long` (`: ulong` beyond `long`). Enums that MAVLink spreads over several
   dialects (`MAV_CMD`) are merged, first definition of an entry wins. AdHoc rejects enums with fewer than two
   members, so such MAVLink enums (11 of them, e.g. `NAV_TAKEOFF_FLAGS`) are emitted as a non-transmittable
   `struct` constants container instead, and fields referencing them stay primitive.
4. **`struct MAV_CMD_PARAMS`** — a non-transmittable Constants Container documenting each command: `value`, the
   boolean entry attributes (`hasLocation`, `isDestination`, `mission`, …) and one nested `struct param_N` per
   `<param>` with `index`, `label`, `units` (a `SI_Unit` reference), `Enum`, `decimalPlaces`, `increment`,
   `minValue`, `maxValue`, `reserved`, `Default`, `description`.
5. **`struct SI_Unit`** — Constants Container of every unit string allowed by the
   [MAVLink XSD schema](https://github.com/ArduPilot/pymavlink/blob/master/generator/mavschema.xsd), grouped
   (time / distance / temperature / angle / electricity / magnetism / energy / power / force / mass / pressure /
   ratio / digital / flow / volume). `[Units(...)]` attributes reference these constants.
6. **Hosts** — `struct GroundControl : Host { }` and `struct MicroAirVehicle : Host { }`, both requesting
   TS / Java / C# / C++ / Go / Rust implementations.
7. **Connection** — `interface CommunicationChannel : Connects<GroundControl, MicroAirVehicle>` with a single
   non-transitional state:
   ```csharp
   [_____lr_____<@<dialect>>(SkipName: @"Attribute$")]
   struct Start { }
   ```
   Either side may send any message; the FSM never transitions. The `SkipName` filter keeps the attribute
   classes of the next section out of the pack set.
8. **Attribute declarations** — the custom attribute classes used by the packs (`UnitsAttribute`,
   `InvalidAttribute`, …). They must live inside the project interface (the agent expects every class to belong
   to a project), which is why the connection filters them out by name.

## Field metadata as custom attributes

AdHoc turns any custom attribute into constants attached to the entity in the generated code, so the MAVLink
metadata that previously survived only inside doc comments is now machine-readable on every host:

| MAVLink XML attribute      | Emitted                        | Notes                                                         |
|:---------------------------|:-------------------------------|:--------------------------------------------------------------|
| `units="m/s"`              | `[Units(SI_Unit.distance.m_s)]` | Falls back to a string literal for a unit unknown to the XSD  |
| `invalid="UINT16_MAX"`     | `[Invalid("UINT16_MAX")]`      | Array notations (`[0]`, `[v:]`, `[a,,c,]`) are passed through  |
| `instance="true"`          | `[Instance]`                   |                                                               |
| `display="bitmask"`        | `[Display("bitmask")]`         |                                                               |
| `print_format="0x%04x"`    | `[PrintFormat("0x%04x")]`      |                                                               |
| `default="NaN"`            | `[Default("NaN")]`             |                                                               |
| `multiplier="1E-2"`        | `[Multiplier("1E-2")]`         |                                                               |
| `minValue` / `maxValue`    | `[MinValue(0)]`, `[MaxValue(100)]` | Numeric when parseable, else string. **Not** mapped to AdHoc's `[MinMax]`: MAVLink treats them as UI hints and `invalid` markers routinely lie outside the range, whereas `[MinMax]` is a hard wire limit |
| field after `<extensions/>` | `[ExtensionField]`            | MAVLink 2 only field                                          |

`<deprecated>`, `<wip>` and `<superseded>` notes are appended to the entity's doc comment as
`⚠ DEPRECATED since … , replaced by …: …`, `🚧 WIP` and `⚠ SUPERSEDED …`. Because AdHoc's `KeepDoc` / `SkipDoc`
filters run over doc text, a branch such as `[l____________<@common>(SkipDoc: "⚠")]` excludes every deprecated
or superseded pack in one line.

## Time is a concept, not a counter

`time_boot_ms` and `time_boot_us` are unambiguously an elapsed time since the vehicle booted, so the field takes
an AdHoc `Duration` alias instead of a bare integer, and the generated API hands the application a time value:

```csharp
class MillisecondsSinceBoot : Duration {
    public long     max       => uint.MaxValue;              // MAVLink carries it as uint32, wrapping after ~49.7 days
    public TimeSpan precision => TimeSpan.FromMilliseconds(1);
}
```

`time_usec` deliberately stays a plain integer. MAVLink defines it as **either** a UNIX epoch timestamp **or**
time since boot, with the receiver deciding from its magnitude, so calling it a `DateTime` would assert something
the protocol does not say.

## Why no varint attributes

MAVLink fields are fixed-width on the wire and the schema states no distribution, only units and an occasional
UI range. `[A]` / `[V]` / `[X]` would therefore be a guess, and a wrong guess makes the wire **larger**. Where the
source does state a hard range the converter already bit-packs it through `[MinMax]`. After conversion, the most
valuable hand edit is to add a varint attribute to the fields whose distribution you actually know.

## Type mapping

| MAVLink                   | AdHoc / C#         | Notes                                                       |
|:--------------------------|:-------------------|:------------------------------------------------------------|
| `uint8_t`                 | `byte`             |                                                             |
| `int8_t`                  | `sbyte`            |                                                             |
| `uint8_t_mavlink_version` | `byte`             | The auto-injected protocol-version magic byte               |
| `uint16_t`                | `ushort`           |                                                             |
| `int16_t`                 | `short`            |                                                             |
| `uint32_t`                | `uint`             |                                                             |
| `int32_t`                 | `int`              |                                                             |
| `uint64_t`                | `ulong`            |                                                             |
| `int64_t`                 | `long`             |                                                             |
| `float`                   | `float`            |                                                             |
| `double`                  | `double`           |                                                             |
| `char`                    | `char`             |                                                             |
| `<T>[N]`                  | `[D(N)] <T>[]`     | Fixed-size array; `<T>` is the translated scalar            |
| `char[N]`                 | `[D(+N)] string`   | Null-padded fixed-length string (variable up to N chars)    |
| `<enum>` field            | `<enum> name;`     | `<field type="..." enum="X">` resolves to `X` when `X` is a real (2+ member) enum of the dialect; otherwise the primitive type is kept and a trailing comment says why |

## Naming

AdHoc rejects entity names that are keywords in any target language (C#, C++, Java, TypeScript, Rust, Go) and
names that start or end with `_`. The converter applies the same `brush` rule AdHocAgent uses — capitalise the
first lowercase letter until the name is free — with the same keyword list, so the agent never has to rename
anything (`type` → `Type`, `fixed` → `Fixed`, `range` → `Range`, `arguments` → `Arguments`).

## Tag handling

| XML tag                                   | Behaviour                                                                                                       |
|:------------------------------------------|:----------------------------------------------------------------------------------------------------------------|
| `<include>`                               | Resolved relative to the including file and parsed first, once; its packs / enums merge into the dialect        |
| `<version>`, `<dialect>`                  | Emitted as a header comment (the top-level file wins over included ones)                                        |
| `<message>`                               | `class <NAME> { … }` with `const int mavlink_message_id`, plus an id-less Dashboard entry                       |
| `<field>`                                 | Typed field with metadata attributes; the tag body becomes the doc comment                                      |
| `<extensions/>`                           | Every following field gets `[ExtensionField]`                                                                   |
| `<enum>`                                  | `enum <NAME>`; `bitmask="true"` → `[Flags]`; merged across dialects; < 2 entries → constants container          |
| `<entry>`                                 | `<NAME> = <value>,` (hex kept as written); boolean attributes go to `MAV_CMD_PARAMS.<NAME>`                      |
| `<param>`                                 | `struct param_<index>` inside `MAV_CMD_PARAMS.<NAME>`; duplicate indexes keep the first occurrence              |
| `<description>`                           | `/** … */` doc comment; `&`, `<`, `>` are XML-escaped because AdHocAgent parses docs as XML fragments          |
| `<deprecated>`, `<wip>`, `<superseded>`   | Appended to the parent's doc comment (see above)                                                                |

## Build & run

Java 17+ JDK required.

```bash
# 1. Compile
javac -encoding UTF-8 -d out src/org/unirail/MavLink2AdHoc.java

# 2. Run — first argument: folder with MAVLink XML files; optional second: output folder
#    (default <cwd>/AdHoc). One <dialect>.cs is written per top-level XML.
java -cp out org.unirail.MavLink2AdHoc msgs            # → ./AdHoc/<dialect>.cs
java -cp out org.unirail.MavLink2AdHoc msgs /some/dir  # → /some/dir/<dialect>.cs

# 3. Feed any generated descriptor to AdHocAgent for cross-language code generation:
AdHocAgent.exe AdHoc/common.cs
```

## Validating the output

AdHocAgent can build and validate a descriptor locally without uploading it: set `ADHOC_PARSE_ONLY=1`.
`ADHOC_DUMP_BRANCHES=1` additionally writes `branches.dump.txt` next to the file with every pack each branch
collected (useful to confirm the pack set and the ids). The agent rewrites the `.cs` in place (it adds its
`/*uid*/` markers), so run it on a copy:

```bash
cp AdHoc/common.cs /tmp/common.cs
ADHOC_PARSE_ONLY=1 ADHOC_DUMP_BRANCHES=1 AdHocAgent.exe /tmp/common.cs
```

The agent prints `INF … enum appears to be a flags enum` for enums whose values happen to be powers of two but
which MAVLink does not declare as `bitmask="true"`; the converter deliberately follows the upstream declaration.

## Refreshing the XML dialects

The `msgs/` directory mirrors `https://github.com/mavlink/mavlink/tree/master/message_definitions/v1.0`
(`matrixpilot.xml` and `ualberta.xml` were removed upstream and are no longer part of the mirror). To pull the
latest:

```bash
cd msgs
for f in ASLUAV AVSSUAS all ardupilotmega common csAirLink cubepilot development \
         icarous loweheiser marsh minimal paparazzi python_array_test standard \
         stemstudios storm32 test uAvionix; do
    curl -sSf -o "${f}.xml" \
        "https://raw.githubusercontent.com/mavlink/mavlink/master/message_definitions/v1.0/${f}.xml"
done
```

If upstream adds new dialects, append their bare names to the list above. If the XSD gains new units, add them to
the `SI_UNITS` table in the converter (unknown units still work: they are emitted as string literals).
