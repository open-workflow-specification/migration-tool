# SpecConvert — Conversion Logic Notes

CNCF Serverless Workflow **0.8 → 1.0** | `src/main/java/com/specconvert/SpecConvert.java`

---

## Processing Pipeline

```
1. Read  →  2. Convert  →  3. Serialise  →  4. Validate  →  5. Write report
```

The input file is parsed into a 0.8 SDK object (`io.serverlessworkflow.api.Workflow`). The `convert()` method builds a **new** 1.0 SDK object tree — nothing from the source is mutated. The output is serialised to the format implied by `-f` (default `yaml`). After the file is written, `OutputValidator` reads it back as a `JsonNode` and validates the structural correctness of every translated element. Findings are forwarded to the `ReportCollector` and included in the migration report.

---

## CLI Usage

```
swf-migrate <input-file> [options]

  -o, --output           Output file path (default: <input-stem>-migrated.yaml)
  -f, --format           Output format: yaml or json (default: yaml)
  -n, --namespace        Namespace in the 1.0 document header (default: default)
  -r, --report           Report file path (default: <input-stem>-report.json|md)
      --report-format    Report format: json or markdown (default: json)
      --strict           Treat warnings as failures; exit 1 if any warnings occur (default: false)
```

### Argument validation

- `-o` and `-f` must agree on extension. Passing `-o out.json -f yaml` (or vice-versa) throws an `IllegalArgumentException` before any conversion work begins.
- `--report` and `--report-format` are subject to the same check (`.json` ↔ `json`, `.md`/`.markdown` ↔ `markdown`).

---

## Top-level Structure

The 0.8 document is a flat object. The 1.0 document wraps everything inside two top-level keys: `document` and `do`.

**0.8 input**
```json
{
  "id": "helloworld",
  "version": "1.0",
  "specVersion": "0.8",
  "namespace": "default",
  "states": [ ... ]
}
```

**1.0 output**
```json
{
  "document": {
    "dsl": "1.0.0",
    "namespace": "default",
    "name": "helloworld",
    "version": "1.0"
  },
  "do": [ ... ]
}
```

---

## `document` Block — Field Mappings

| 0.8 field      | 1.0 field            | Notes                                     |
|----------------|----------------------|-------------------------------------------|
| `specVersion`  | `document.dsl`       | Hard-coded to `"1.0.0"`                   |
| `id`           | `document.name`      | Direct copy; defaults to `"unnamed"`      |
| `namespace`    | `document.namespace` | Set from `-n` flag; defaults to `"default"` |
| `version`      | `document.version`   | Direct value copy; defaults to `"0.0.1"`  |

---

## `do` Block — State Conversion

The 0.8 `states` array becomes a 1.0 `do` array. Each state becomes a single-key object keyed by the state's `name`.

| 0.8 state type | 1.0 task type                      | Transformer class            |
|----------------|------------------------------------|------------------------------|
| `inject`       | `set`                              | `Inject`                     |
| `sleep`        | `wait`                             | `Sleep`                      |
| `switch`       | `switch` (data) / `do [ listen + switch ]` (event) | `Switch`      |
| `parallel`     | `fork`                             | `Parallel`                   |
| `operation`    | `do` (sequential) / `fork` (parallel) | `Operation`               |
| `event`        | `listen`                           | `Event`                      |
| `forEach`      | `for`                              | `ForEach`                    |
| `callback`     | `do [ call + listen + switch ]`    | `Callback`                   |

Any state type not listed above is skipped with an `[ERROR]` report entry and a manual task.

---

## State Conversion Details

### `inject` → `set`

The state's `data` object is copied directly into a `set` wrapper.

```yaml
# 0.8
name: Hello State
type: inject
data:
  result: Hello World!

# 1.0
Hello State:
  set:
    result: Hello World!
```

---

### `sleep` → `wait`

The `duration` ISO 8601 string is parsed into discrete `DurationInline` components. Years and months have no `DurationInline` fields and are folded into `days` (years × 365, months × 30). Zero-valued components are suppressed from the output.

```yaml
# 0.8
name: SleepFiveSeconds
type: sleep
duration: PT5S

# 1.0
SleepFiveSeconds:
  wait:
    seconds: 5
```

| Input      | Output `wait`                          |
|------------|----------------------------------------|
| `PT5S`     | `seconds: 5`                           |
| `PT1H30M`  | `hours: 1, minutes: 30`               |
| `P2DT3H4M` | `days: 2, hours: 3, minutes: 4`       |
| `P1Y`      | `days: 365` (approximate)              |

---

### `switch` → `switch` / `do [ listen + switch ]`

The translation depends on which condition type the state uses.

#### Data conditions (`dataConditions`) → plain `switch`

Each `dataConditions` entry becomes a named switch case with a `when` predicate on the current workflow data. The `defaultCondition` becomes the `"default"` case.

```yaml
# 0.8
name: CheckApplicant
type: switch
dataConditions:
  - name: Applicant is adult
    condition: "${ .age >= 18 }"
    transition: ApproveApplication
defaultCondition:
  transition: RejectApplication

# 1.0
CheckApplicant:
  switch:
    - applicantIsAdult:
        when: .age >= 18
        then: ApproveApplication
    - default:
        then: RejectApplication
```

Condition names are **camelCased** for use as YAML keys (e.g. `"Applicant is adult"` → `applicantIsAdult`).

##### EL expression handling

0.8 conditions written as `${ ... }` are not valid jq. The converter:
1. Strips the `${ }` wrapper from the `when` value.
2. Logs a `[WARN]` to stderr.
3. Adds a `WARNING / expression_conversion` issue to the migration report.

These conditions require **manual translation** to jq syntax before the workflow will run correctly.

---

#### Event conditions (`eventConditions`) → `do [ listen + switch ]`

An event-based switch waits for one of N events and routes based on which arrived. The correct 1.0 idiom is a two-step composite:

1. **`listen`** with `any: [...]` — one filter per `eventRef`, resolved to a CloudEvent `type`. Blocks until one of the listed events arrives and makes the received event available as task output.
2. **`switch`** — one trivial `when: .type == "<cloudEventType>"` case per `eventCondition`, routing to the transition target. The `defaultCondition` becomes the `"default"` case.

The `eventRef` name is resolved against the workflow's top-level `events` definitions to obtain the CloudEvent `type`; if no definition is found the `eventRef` name is used as-is. The `eventRef` name is lowercased to form the switch case key.

```yaml
# 0.8
name: CheckVisaStatus
type: switch
eventConditions:
  - eventRef: visaApprovedEvent
    transition: HandleApprovedVisa
  - eventRef: visaRejectedEvent
    transition: HandleRejectedVisa
defaultCondition:
  transition: HandleNoVisaDecision

# 1.0
CheckVisaStatus:
  do:
    - CheckVisaStatusListen:
        listen:
          to:
            any:
              - with:
                  type: visaApprovedEvent
              - with:
                  type: visaRejectedEvent
    - CheckVisaStatusRoute:
        switch:
          - visaapprovedevent:
              when: .type == "visaApprovedEvent"
              then: HandleApprovedVisa
          - visarejectedevent:
              when: .type == "visaRejectedEvent"
              then: HandleRejectedVisa
          - default:
              then: HandleNoVisaDecision
```

Each `EventCondition` may have a `transition` **or** an `end` marker. If `end` is present, the case `then` is set to `"end"`. If neither is present a `"TODO"` placeholder is emitted with a `WARNING / state_transformation` report entry.

---

### `parallel` → `fork`

Each named branch becomes a `do` task inside `fork.branches`. The `completionType` field controls `compete`:

| 0.8 `completionType` | 1.0 `fork.compete` | Meaning                          |
|----------------------|--------------------|----------------------------------|
| `allOf` (default)    | `false`            | All branches must finish         |
| `atLeast`            | `true`             | First branch to finish wins      |

```yaml
# 0.8
name: ParallelExec
type: parallel
completionType: allOf
branches:
  - name: BranchA
    actions:
      - functionRef: { refName: doA }
  - name: BranchB
    actions:
      - functionRef: { refName: doB }

# 1.0
ParallelExec:
  fork:
    compete: false
    branches:
      - BranchA:
          do:
            - doA:
                call: doA
                with: {}
      - BranchB:
          do:
            - doB:
                call: doB
                with: {}
```

---

### `operation` → `do` / `fork`

The `actionMode` field determines the task type:

| 0.8 `actionMode`      | 1.0 task type | Structure                                      |
|-----------------------|---------------|------------------------------------------------|
| `sequential` (default)| `do`          | Actions in order inside a `do` task            |
| `parallel`            | `fork`        | Each action becomes its own branch (`compete: false`) |

Each action's `functionRef` becomes a `call` task keyed by `refName`.

```yaml
# 0.8 sequential
name: CallServices
type: operation
actionMode: sequential
actions:
  - name: stepOne
    functionRef: { refName: serviceA, arguments: { id: "${ .id }" } }
  - name: stepTwo
    functionRef: { refName: serviceB }

# 1.0
CallServices:
  do:
    - stepOne:
        call: serviceA
        with:
          id: "${ .id }"
    - stepTwo:
        call: serviceB
        with: {}
```

Actions with no `functionRef` emit a placeholder `set` task with a `_warning` property and a `WARNING / unsupported_feature` report entry.

---

### `event` → `listen`

The `exclusive` flag maps to the consumption strategy:

| 0.8 `exclusive` | 1.0 `listen.to` key | Meaning                                    |
|-----------------|---------------------|--------------------------------------------|
| `true` (default)| `any`               | First matching event triggers the state    |
| `false`         | `all`               | All listed events must arrive              |

Each `eventRef` in `onEvents` is resolved against the workflow's top-level `events` definitions to obtain the CloudEvent `type` string. If no definition is found, the `eventRef` name is used as-is.

If any `onEvents` entry has `actions`, a `foreach` iterator is attached to the `listen` task. All actions across all `onEvents` entries are flattened into a single `do` list. The iteration variable is `"item"` (the received CloudEvent).

```yaml
# 0.8
name: WaitForApproval
type: event
exclusive: true
onEvents:
  - eventRefs: [approvalEvent]
    actions:
      - functionRef: { refName: logApproval }

# 1.0
WaitForApproval:
  listen:
    to:
      any:
        - with:
            type: com.example.approval.received
  foreach:
    item: item
    do:
      - logApproval:
          call: logApproval
          with: {}
```

---

### `forEach` → `for`

| 0.8 field          | 1.0 field   | Notes                                                   |
|--------------------|-------------|---------------------------------------------------------|
| `inputCollection`  | `for.in`    | Collection expression; defaults to `${ .[] }` if absent |
| `iterationParam`   | `for.each`  | Iteration variable; defaults to `"item"` if absent      |
| `actions`          | `do`        | Each action converted via `util.convertAction()`        |
| `outputCollection` | —           | No 1.0 equivalent; dropped with `WARNING` report entry  |
| `batchSize`        | —           | No 1.0 equivalent; dropped with `WARNING` report entry  |

```yaml
# 0.8
name: ProcessOrders
type: forEach
inputCollection: "${ .orders }"
iterationParam: order
actions:
  - functionRef: { refName: processOrder, arguments: { id: "${ .order.id }" } }

# 1.0
ProcessOrders:
  for:
    each: order
    in: "${ .orders }"
  do:
    - processOrder:
        call: processOrder
        with:
          id: "${ .order.id }"
```

---

### `callback` → `do [ call + listen + switch ]`

A callback state is expanded into a three-step composite `do` task. A `high`-priority manual task is always added to the migration report asking the operator to verify the converted semantics.

**Steps:**

1. **call** (optional) — the outgoing `action` becomes a `call` task. Omitted if the state has no `action`.
2. **listen** — waits for the CloudEvent named by `eventRef`. The event `type` is resolved from the workflow's top-level `events` definitions; the `eventRef` name is used as-is if no definition is found.
3. **switch** — routes on the received event's `type` field:
   - Named case `callbackReceived`: `when: ${ .type == "<cloudEventType>" }` → transition target.
   - Default case: `then: end` (fallback for unexpected outcomes).

**Transition target resolution:**

| 0.8 state field            | 1.0 `then` value              |
|----------------------------|-------------------------------|
| `transition.nextState`     | Named next state              |
| `end` (any truthy value)   | `"end"`                       |
| Neither present            | `"TODO"` + `ERROR` report entry |

```yaml
# 0.8
name: RequestVitals
type: callback
action:
  name: sendVitalsRequest
  functionRef:
    refName: sendVitalsRequest
    arguments:
      patientId: "${ .patientId }"
eventRef: VitalsReceived
transition: ProcessVitals

# 1.0
RequestVitals:
  do:
    - sendVitalsRequest:
        call: sendVitalsRequest
        with:
          patientId: "${ .patientId }"
    - RequestVitalsListen:
        listen:
          to:
            any:
              - with:
                  type: com.hospital.vitals.received
    - RequestVitalsRoute:
        switch:
          - callbackReceived:
              when: "${ .type == \"com.hospital.vitals.received\" }"
              then: ProcessVitals
          - default:
              then: end
```

---

## Action Conversion (`util.convertAction`)

Used by `operation`, `forEach`, `event` (foreach body), `parallel`, and `callback`. A `functionRef` action becomes a `call` task:

```yaml
# action with functionRef
- stepOne:
    call: myFunction
    with:
      argA: value1
      argB: value2
```

If the action has no `functionRef`, a placeholder `set` task is emitted with `_warning: "unsupported action type"` and a `WARNING / unsupported_feature` entry is added to the report.

---

## Event Type Resolution

At the start of `buildDo()`, a `Map<String, String>` of `eventRef name → CloudEvent type` is built from the workflow's top-level `events` block. Both `Listen` and `Callback` transformers use this map to resolve symbolic event names to their CloudEvent `type` strings. If a name has no definition, the name itself is used as the type.

---

## ISO 8601 Duration Parsing

Used by the `sleep` → `wait` conversion. The regex `P(?:(\d+)Y)?(?:(\d+)M)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?` is applied and each component is stored as a discrete `DurationInline` field. Years and months are folded into days (approximate: 1y = 365d, 1mo = 30d).

---

## Output Validation

After the converted file is written, `OutputValidator` (`src/main/java/com/specconvert/validator/OutputValidator.java`) reads it back as a `JsonNode` and checks every translated element. Findings become `validation`-category issues in the migration report.

| Task type | Key checks                                                                 |
|-----------|---------------------------------------------------------------------------|
| `document`| `dsl` = `"1.0.0"`; `name`, `namespace` non-blank; `version` present       |
| `set`     | `set` field is a non-null object                                           |
| `wait`    | `wait` object present; at least one positive duration component            |
| `switch`  | Non-empty array; exactly one `default`; all non-default cases have `when`; all cases have `then`; no `TODO` placeholders |
| `call`    | `call` is a non-blank string; `with` (if present) is an object            |
| `for`     | `for.in` and `for.each` non-blank; `do` present and non-empty             |
| `fork`    | `fork.branches` non-empty array; `fork.compete` is a boolean              |
| `listen`  | `listen.to` has exactly one of `any`/`all`; array non-empty; each filter has `with.type` |
| `do`      | `do` array non-empty; recursively validated                               |

Validator findings are printed to stderr alongside converter output and contribute to `warnings_count`/`errors_count` in the report summary.

---

## Migration Report

Written alongside the converted file. Format is controlled by `--report-format` (`json` default, `markdown` also supported).

### `overall_status` values

| Value                    | Condition                                             |
|--------------------------|-------------------------------------------------------|
| `success`                | No errors, no warnings                                |
| `success_with_warnings`  | Warnings present, `--strict false` (default)          |
| `failed`                 | Warnings present and `--strict true`                  |
| `partial`                | One or more `ERROR`-severity issues                   |

### Issue categories

| Category               | Source                                                  |
|------------------------|---------------------------------------------------------|
| `expression_conversion`| EL `${ }` expression stripped; needs jq translation    |
| `state_transformation` | Transition/end missing on a state                       |
| `data_flow`            | Reserved for data mapping issues                        |
| `error_handling`       | Reserved for error/retry mapping issues                 |
| `authentication`       | Reserved for auth-related issues                        |
| `unsupported_feature`  | State type has no 1.0 equivalent; field was dropped     |
| `extension`            | Reserved for extension-related issues                   |
| `validation`           | Structural defect detected in the serialised output     |

Manual tasks (always `high` priority) are emitted for callback states to prompt human review of the converted composite flow.
