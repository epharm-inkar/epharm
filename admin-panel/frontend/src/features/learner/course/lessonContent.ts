// Lesson text as readable blocks. Course texts are plain lines typed in the admin; this turns them
// into short parts (by the «1.1.» headings) with lists, warnings, examples and numbered steps, so a
// pharmacist reads one screen at a time instead of a wall of text (Grigoriy, 25.11.2026).

export type ContentBlock =
  | { kind: 'paragraph'; text: string }
  | { kind: 'list'; lead?: string; items: string[]; ordered: boolean }
  | { kind: 'definitions'; items: Array<{ term: string; text: string }> }
  | { kind: 'callout'; tone: 'warning' | 'example' | 'tip'; title: string; text: string }
  | { kind: 'steps'; items: Array<{ title: string; text: string }> }
  | { kind: 'quote'; text: string }

export type ContentSection = {
  /** Null for the text before the first heading: it is shown without a made-up title. */
  title: string | null
  blocks: ContentBlock[]
}

const SECTION_HEADING = /^\d+(?:\.\d+)+(?:[.)])?\s+\S/
const BULLET = /^[-•–—]\s+/
const NUMBERED = /^\d+[.)]\s+/
const STEP = /^Шаг\s+\d+\.\s*(.+)$/i
const DEFINITION = /^([А-ЯЁA-Z][^:.!?]{1,48}):\s+(\S.*)$/
const QUOTE = /^«[^»]+»\.?$/
/** Longer lines are paragraphs, never list items. */
const LIST_ITEM_MAX = 220
/** Sentences up to this length in a run under a «…:» line read as list items. */
const SHORT_ITEM_MAX = 140

// JavaScript's \b does not see Cyrillic letters, so word ends are written out.
const WORD_END = '(?=[\\s:.,—–-]|$)'
const CALLOUTS: Array<{
  tone: 'warning' | 'example' | 'tip'
  pattern: RegExp
  fallbackTitle: string
}> = [
  {
    tone: 'warning',
    pattern: new RegExp(`^(Запрещено|Нельзя|Не следует|Важно|Внимание)${WORD_END}`, 'i'),
    fallbackTitle: 'Важно',
  },
  {
    tone: 'example',
    pattern: new RegExp(`^(Пример из практики|Пример сообщения|Пример|Ситуация)${WORD_END}`, 'i'),
    fallbackTitle: 'Пример',
  },
  {
    tone: 'tip',
    pattern: new RegExp(
      `^(Правильное решение|Правильный порядок действий|Правильный алгоритм|Главный приоритет)${WORD_END}`,
      'i',
    ),
    fallbackTitle: 'Как правильно',
  },
]
const LABEL_SEPARATOR = /^\s*[:.—–-]\s*/

const endsSentence = (line: string) => /[.!?»]$/.test(line)
const capitalize = (text: string) => text.charAt(0).toUpperCase() + text.slice(1)

/**
 * «Запрещено: …», «Пример из практики. …», «Главный приоритет — …» become a titled box with the rest
 * as text; a sentence that merely starts with «Нельзя …» stays whole under a short title.
 * An empty text means the box takes the next line («Пример сообщения:» + the quote).
 */
function calloutOf(line: string): Extract<ContentBlock, { kind: 'callout' }> | null {
  for (const { tone, pattern, fallbackTitle } of CALLOUTS) {
    const match = line.match(pattern)
    if (!match) continue
    const after = line.slice(match[0].length)
    const separator = after.match(LABEL_SEPARATOR)
    if (separator && /[:.—–-]/.test(separator[0])) {
      return {
        kind: 'callout',
        tone,
        title: capitalize(match[1].toLowerCase()),
        text: capitalize(after.slice(separator[0].length).trim()),
      }
    }
    return { kind: 'callout', tone, title: fallbackTitle, text: line }
  }
  return null
}

export function parseLessonContent(content: string): ContentBlock[] {
  const lines = content
    .replace(/\r/g, '')
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
  const blocks: ContentBlock[] = []
  let index = 0

  while (index < lines.length) {
    const line = lines[index]

    if (SECTION_HEADING.test(line)) {
      blocks.push({ kind: 'paragraph', text: `\u0000${line}` })
      index += 1
      continue
    }

    const step = line.match(STEP)
    if (step) {
      const items: Array<{ title: string; text: string }> = []
      while (index < lines.length) {
        const current = lines[index].match(STEP)
        if (!current) break
        const next = lines[index + 1]
        const hasText = next && !STEP.test(next) && !SECTION_HEADING.test(next)
        items.push({ title: current[1].trim(), text: hasText ? next : '' })
        index += hasText ? 2 : 1
      }
      blocks.push({ kind: 'steps', items })
      continue
    }

    if (BULLET.test(line) || NUMBERED.test(line)) {
      const ordered = NUMBERED.test(line)
      const items: string[] = []
      while (index < lines.length) {
        const candidate = lines[index]
        if (!(ordered ? NUMBERED.test(candidate) : BULLET.test(candidate))) break
        items.push(candidate.replace(ordered ? NUMBERED : BULLET, '').trim())
        index += 1
      }
      blocks.push({ kind: 'list', items, ordered })
      continue
    }

    if (line.endsWith(':')) {
      const items: string[] = []
      let cursor = index + 1
      while (cursor < lines.length) {
        const candidate = lines[cursor]
        if (
          SECTION_HEADING.test(candidate) ||
          candidate.endsWith(':') ||
          STEP.test(candidate) ||
          calloutOf(candidate) ||
          candidate.length > LIST_ITEM_MAX
        )
          break
        items.push(candidate.replace(BULLET, '').replace(NUMBERED, '').trim())
        cursor += 1
        // Items end with «;» or «,». Short sentences in a row («Не прикасаться к оборудованию.»)
        // are items too; a long or a differently shaped next line ends the list.
        if (!BULLET.test(candidate) && !NUMBERED.test(candidate) && endsSentence(candidate)) {
          const following = lines[cursor]
          const shortRun =
            candidate.length <= SHORT_ITEM_MAX &&
            !!following &&
            following.length <= SHORT_ITEM_MAX &&
            !SECTION_HEADING.test(following) &&
            !following.endsWith(':') &&
            !STEP.test(following) &&
            !calloutOf(following) &&
            !QUOTE.test(following)
          if (!shortRun) break
        }
      }
      if (items.length >= 2) {
        const quotes = items.every((item) => QUOTE.test(item))
        if (quotes) {
          blocks.push({ kind: 'paragraph', text: line })
          items.forEach((item) => blocks.push({ kind: 'quote', text: item }))
        } else {
          const definitions = items.map((item) => item.match(DEFINITION))
          if (definitions.every(Boolean)) {
            blocks.push({ kind: 'paragraph', text: line })
            blocks.push({
              kind: 'definitions',
              items: definitions.map((match) => ({
                term: match![1].trim(),
                text: match![2].trim(),
              })),
            })
          } else {
            blocks.push({ kind: 'list', lead: line, items, ordered: false })
          }
        }
        index = cursor
        continue
      }
    }

    const callout = calloutOf(line)
    if (callout) {
      // «Пример сообщения:» on its own line takes the next line as its text.
      if (!callout.text) {
        const next = lines[index + 1]
        if (next && !SECTION_HEADING.test(next)) {
          blocks.push({ ...callout, text: next })
          index += 2
          continue
        }
        blocks.push({ kind: 'paragraph', text: line })
        index += 1
        continue
      }
      blocks.push(callout)
      index += 1
      continue
    }

    if (QUOTE.test(line)) {
      blocks.push({ kind: 'quote', text: line })
      index += 1
      continue
    }

    // «Term: explanation» lines in a row read as a small table (roles, product groups).
    if (DEFINITION.test(line) && lines[index + 1] && DEFINITION.test(lines[index + 1])) {
      const items: Array<{ term: string; text: string }> = []
      while (index < lines.length) {
        const match = lines[index].match(DEFINITION)
        if (!match) break
        items.push({ term: match[1].trim(), text: match[2].trim() })
        index += 1
      }
      blocks.push({ kind: 'definitions', items })
      continue
    }

    // A short title line followed by its explanation («Неисправное электрооборудование» + sentence).
    const next = lines[index + 1]
    if (
      line.length <= 60 &&
      !endsSentence(line) &&
      !line.endsWith(';') &&
      next &&
      endsSentence(next) &&
      !SECTION_HEADING.test(next)
    ) {
      const items: Array<{ term: string; text: string }> = []
      while (index < lines.length) {
        const title = lines[index]
        const text = lines[index + 1]
        if (
          !title ||
          !text ||
          title.length > 60 ||
          endsSentence(title) ||
          title.endsWith(';') ||
          title.endsWith(':') ||
          SECTION_HEADING.test(title) ||
          !endsSentence(text)
        )
          break
        items.push({ term: title, text })
        index += 2
      }
      if (items.length >= 2) {
        blocks.push({ kind: 'definitions', items })
        continue
      }
      // A single pair stays two ordinary lines.
      index -= items.length * 2
    }

    blocks.push({ kind: 'paragraph', text: line })
    index += 1
  }

  return blocks
}

/** Parts of a lesson, split at the «1.1.» headings. */
export function splitIntoSections(blocks: ContentBlock[]): ContentSection[] {
  const sections: ContentSection[] = []
  let current: ContentSection = { title: null, blocks: [] }
  for (const block of blocks) {
    if (block.kind === 'paragraph' && block.text.startsWith('\u0000')) {
      if (current.blocks.length > 0 || current.title) sections.push(current)
      current = { title: block.text.slice(1), blocks: [] }
      continue
    }
    current.blocks.push(block)
  }
  if (current.blocks.length > 0 || current.title || sections.length === 0) sections.push(current)
  return sections.filter((section) => section.blocks.length > 0 || section.title)
}

export function lessonSections(content: string): ContentSection[] {
  return splitIntoSections(parseLessonContent(content))
}

function blockWords(block: ContentBlock): number {
  const count = (text: string) => text.split(/\s+/).filter(Boolean).length
  switch (block.kind) {
    case 'paragraph':
    case 'quote':
      return count(block.text)
    case 'callout':
      return count(block.title) + count(block.text)
    case 'list':
      return count(block.lead ?? '') + block.items.reduce((sum, item) => sum + count(item), 0)
    case 'definitions':
    case 'steps':
      return block.items.reduce(
        (sum, item) => sum + count('term' in item ? item.term : item.title) + count(item.text),
        0,
      )
  }
}

/**
 * Seconds before «Дальше» opens on a part read for the first time: about 190 words a minute, at
 * least 5 and at most 40 seconds. Enough to read, short enough not to annoy a fast reader.
 */
export function readingSeconds(section: ContentSection): number {
  const words =
    section.blocks.reduce((sum, block) => sum + blockWords(block), 0) + (section.title ? 4 : 0)
  return Math.min(40, Math.max(5, Math.round(words / 3.2)))
}
