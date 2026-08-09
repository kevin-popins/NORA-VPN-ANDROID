# Codex App UI Director V6 — Scene Fidelity

V6 сохраняет сильное ядро V5 для WPF, desktop UI, визуальных систем, motion design и runtime-проверки. Новая версия не меняет методику с нуля: она добавляет отдельный контур для сайтов, лендингов, hero-сцен и публичных интерактивных демо.

## Что добавлено в V6

- source fidelity: сначала изучить доступный код, runtime, состояния и внутренние анимации, потом адаптировать;
- запрет подменять интерактивный продукт скриншотами и скрытыми зонами клика;
- scene-first композиция: один визуальный тезис, доминирующая форма, контролируемая глубина и негативное пространство;
- construction-based reveal: trace/mask/assembly/light handoff/settle вместо fade-only;
- отдельная проверка фоновых решений и их неудачных анти-паттернов;
- поиск selection/hover/focus/scroll артефактов у gradient/filter/blend/pseudo-element слоёв;
- непрерывность фона между hero и следующими секциями;
- различие idle motion и hover motion;
- full desktop, mobile-lite и reduced-motion режимы;
- приватность публичных демо и обязательные synthetic/local state;
- checkpoint перед экспериментами, чтобы не ломать уже одобренный вариант.

## Основные новые references

```text
.agents/skills/app-ui-director/references/
  SOURCE_FIDELITY_AND_SCOPE.md
  SCENE_COMPOSITION_AND_REVEAL.md
  WEB_BACKGROUND_AND_EFFECT_ARTIFACTS.md
  WEB_MOTION_AND_PERFORMANCE.md
  DEMO_PRIVACY.md
```

## Установка в проект

Скопируйте с заменой в корень проекта:

```text
AGENTS.md
.agents/
```

Получится:

```text
your-project/
  AGENTS.md
  .agents/
    skills/
      app-ui-director/
        SKILL.md
        agents/openai.yaml
        references/
        templates/
```

После копирования начните новую задачу Codex, чтобы обновлённый skill был загружен в контекст.

## Главная формула V6

```text
Inspect before inventing.
Compose one scene before containers.
Reveal by construction, not fade alone.
Background layer bounds are UI bugs.
Idle motion is not hover motion.
Runtime proof includes selection, scroll, mobile, and time.
```

## Пример для сайта

```text
Use the app-ui-director skill.

Preserve product behavior and accepted work.
Inspect the available source before adapting it.
Define one dominant visual idea, the scene layer topology, and the transition below the fold.

Prove entrance, settle, and idle motion in runtime.
Inspect text selection, hover, focus, scroll, and responsive breakpoints for effect artifacts.
Provide full desktop, mobile-lite, and reduced-motion modes.
Fix visible layer bounds and seams at the root topology.
```
