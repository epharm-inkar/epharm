import { describe, expect, it } from 'vitest'
import { lessonSections, parseLessonContent, readingSeconds } from './lessonContent'

const MODULE_1 = `1.1. Что такое пожарная безопасность

Пожарная безопасность — это состояние защищённости людей, имущества и помещений от пожаров и их последствий.

В аптеке пожарная безопасность включает три направления:

Профилактика: предотвращение условий, при которых может возникнуть пожар.

Раннее обнаружение: своевременное выявление дыма, запаха гари и срабатывания сигнализации.

Безопасное реагирование: сообщение экстренным службам, эвакуация людей.

Главный приоритет — жизнь и здоровье людей. Сохранность товара не должна ставиться выше безопасности.

1.3. Основные причины пожаров

Неисправное электрооборудование

Повреждённые кабели, неисправные розетки, перегрев блоков питания.

Нарушения хранения

Скопление картона и упаковки возле электрооборудования.

1.4. Что обязан делать каждый сотрудник

Каждый работник аптеки должен:

знать расположение ближайших выходов и путей эвакуации;

не загромождать проходы, выходы и доступ к средствам пожаротушения;

выполнять указания руководителя эвакуации и пожарных подразделений.

Запрещено: игнорировать сигнал тревоги, самостоятельно блокировать выходы.`

const MODULE_5 = `Алгоритм «Обнаружил — сообщил — эвакуировал»

Шаг 1. Обнаружить опасность

Обратить внимание на дым, запах гари, пламя.

Шаг 2. Сообщить о пожаре

Позвонить по номеру 101 или 112 в Казахстане.

5.1. Как правильно сообщить о пожаре

Пример сообщения:

«Произошло возгорание в аптеке по адресу [точный адрес]. Начата эвакуация».

Рекомендуемые фразы:

«Внимание, необходимо покинуть помещение по причине пожарной тревоги».

«Пожалуйста, следуйте к выходу и не задерживайтесь».

3.2. Признаки неисправности

Если сотрудник замечает запах горелой изоляции, искрение или дым:

Не прикасаться к повреждённому оборудованию.

Предупредить находящихся рядом людей.

Сообщить руководителю смены или ответственному лицу.

Нельзя задерживать эвакуацию ради тушения.`

describe('lesson text as readable parts', () => {
  it('splits at «1.1.» headings and keeps the text before the first heading without a made-up title', () => {
    const sections = lessonSections(MODULE_5)
    expect(sections.map((section) => section.title)).toEqual([
      null,
      '5.1. Как правильно сообщить о пожаре',
      '3.2. Признаки неисправности',
    ])
  })

  it('reads «Term: text» rows, short title pairs, lists and warnings', () => {
    const [first, causes, duties] = lessonSections(MODULE_1)
    expect(first.blocks.map((block) => block.kind)).toEqual([
      'paragraph',
      'paragraph',
      'definitions',
      'callout',
    ])
    expect(first.blocks[2]).toMatchObject({
      kind: 'definitions',
      items: [
        { term: 'Профилактика' },
        { term: 'Раннее обнаружение' },
        { term: 'Безопасное реагирование' },
      ],
    })
    expect(first.blocks[3]).toMatchObject({
      kind: 'callout',
      tone: 'tip',
      title: 'Главный приоритет',
    })
    expect(causes.blocks).toEqual([
      {
        kind: 'definitions',
        items: [
          {
            term: 'Неисправное электрооборудование',
            text: 'Повреждённые кабели, неисправные розетки, перегрев блоков питания.',
          },
          {
            term: 'Нарушения хранения',
            text: 'Скопление картона и упаковки возле электрооборудования.',
          },
        ],
      },
    ])
    expect(duties.blocks[0]).toMatchObject({ kind: 'list', lead: 'Каждый работник аптеки должен:' })
    expect((duties.blocks[0] as { items: string[] }).items).toHaveLength(3)
    expect(duties.blocks[1]).toMatchObject({
      kind: 'callout',
      tone: 'warning',
      title: 'Запрещено',
      text: 'Игнорировать сигнал тревоги, самостоятельно блокировать выходы.',
    })
  })

  it('turns «Шаг N.» into steps, examples into boxes and phrases into quotes', () => {
    const blocks = parseLessonContent(MODULE_5)
    expect(blocks[1]).toEqual({
      kind: 'steps',
      items: [
        { title: 'Обнаружить опасность', text: 'Обратить внимание на дым, запах гари, пламя.' },
        { title: 'Сообщить о пожаре', text: 'Позвонить по номеру 101 или 112 в Казахстане.' },
      ],
    })
    const [, message, faults] = lessonSections(MODULE_5)
    expect(message.blocks[0]).toMatchObject({
      kind: 'callout',
      tone: 'example',
      title: 'Пример сообщения',
    })
    expect(message.blocks.filter((block) => block.kind === 'quote')).toHaveLength(2)
    expect(faults.blocks[0]).toMatchObject({ kind: 'list' })
    expect((faults.blocks[0] as { items: string[] }).items).toEqual([
      'Не прикасаться к повреждённому оборудованию.',
      'Предупредить находящихся рядом людей.',
      'Сообщить руководителю смены или ответственному лицу.',
    ])
    expect(faults.blocks[1]).toMatchObject({ kind: 'callout', tone: 'warning', title: 'Важно' })
  })

  it('asks for a few seconds of reading per part, never more than 40', () => {
    const [first] = lessonSections(MODULE_1)
    expect(readingSeconds(first)).toBeGreaterThanOrEqual(5)
    expect(readingSeconds(first)).toBeLessThanOrEqual(40)
    expect(readingSeconds({ title: null, blocks: [{ kind: 'paragraph', text: 'Коротко.' }] })).toBe(
      5,
    )
  })

  it('a lone stray line before the first heading does not become a section called «Введение»', () => {
    const sections = lessonSections('4.1. Средства\n\nОгнетушители и сигнализация.')
    expect(sections).toHaveLength(1)
    expect(sections[0].title).toBe('4.1. Средства')
  })
})
