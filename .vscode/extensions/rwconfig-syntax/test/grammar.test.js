'use strict';

/**
 * Tests for the rwconfig TextMate grammar.
 *
 * These check what an editor highlights, which is not quite the same question
 * as what the parser accepts - see `ConfigFileTest.java` for that side. Where
 * the two intentionally differ, the test says so.
 */

const assert = require('node:assert/strict');
const { before, describe, test } = require('node:test');

const { loadGrammar, tokenizeLines, scopesOf, invalidTokens, hasInvalid } = require('./grammar.js');

before(loadGrammar);

/** Assert that the grammar found nothing wrong with `text`. */
async function assertValid(text) {
    const invalid = await invalidTokens(text);
    assert.deepEqual(
        invalid.map((token) => token.text),
        [],
        `expected no invalid tokens in ${JSON.stringify(text)}`
    );
}

/** Assert that the grammar marked something in `text` as invalid. */
async function assertInvalid(text) {
    assert.ok(await hasInvalid(text), `expected an invalid token in ${JSON.stringify(text)}`);
}

/** Assert that `substring` carries a scope ending in `suffix`. */
async function assertScope(text, substring, suffix) {
    const scopes = await scopesOf(text, substring);
    assert.ok(
        scopes.some((scope) => scope.endsWith(suffix)),
        `expected ${JSON.stringify(substring)} to be scoped ${suffix}, but got: ${scopes.join(', ')}`
    );
}


describe('the parts of a declaration', () => {
    test('a type, a name and a value', async () => {
        const line = 'int myProp = 42';
        await assertScope(line, 'int', 'storage.type.rwconfig');
        await assertScope(line, 'myProp', 'variable.other.property-name.rwconfig');
        await assertScope(line, '42', 'constant.numeric.rwconfig');
    });

    test('the type is optional', async () => {
        await assertScope('myProp = hello', 'myProp', 'variable.other.property-name.rwconfig');
        await assertScope('myProp = hello', 'hello', 'string.unquoted.rwconfig');
    });

    test('a comment starts with a # or a !', async () => {
        await assertScope('# a comment', '# a comment', 'comment.line.number-sign.rwconfig');
        await assertScope('! a comment', '! a comment', 'comment.line.number-sign.rwconfig');
    });

    test('a `<<` backreference value stands out', async () => {
        await assertScope('rwc.db.password = <<', '<<', 'markup.bold');
    });

    test('an unknown type is marked invalid', async () => {
        await assertInvalid('nosuchtype myProp = a');
    });

    test('a name that does not start with a letter is marked invalid', async () => {
        await assertInvalid('1stName = a');
        await assertInvalid('.rc = a');
    });

    test('an empty allowed values list is marked invalid', async () => {
        await assertInvalid('string[] myProp = a');
    });
});


describe('lines that end with a backslash are joined with the next line', () => {
    test('the trailing backslash is scoped as a line continuation', async () => {
        await assertScope(
            'string myProp = a\\\nb',
            '\\',
            'constant.character.escape.line-continuation.rwconfig'
        );
    });

    test('the next line is part of the value rather than a new declaration', async () => {
        const text = 'rwc.sources = args, system, \\\n              local, app';
        await assertValid(text);
        await assertScope(text, '              local, app', 'string.unquoted.rwconfig');
    });

    test('a list can be split over several lines', async () => {
        await assertValid('stringList myList = a, b, \\\n   c, \\\n   d');
        await assertScope('intList myList = 1, \\\n   2', '2', 'constant.numeric.rwconfig');
    });

    test('an allowed values list can be split over several lines', async () => {
        const text = 'intList[80, \\\n        1024:65535] myPorts = 1520';
        await assertValid(text);
        await assertScope(text, '1024', 'constant.numeric.rwconfig');
        await assertScope(text, 'myPorts', 'variable.other.property-name.rwconfig');
    });

    test('a comment that ends with a backslash swallows the next line, as the parser does', async () => {
        const lines = await tokenizeLines('# a comment \\\nstring myProp = surprise');
        assert.ok(lines[1].length > 0, 'the swallowed line should have tokens');
        assert.ok(
            lines[1].every((token) =>
                token.scopes.some((s) => s.endsWith('comment.line.number-sign.rwconfig'))
            ),
            `the swallowed line should be part of the comment, but got: ${JSON.stringify(lines[1])}`
        );
    });

    test('an empty line ends the joined line', async () => {
        const lines = await tokenizeLines('string myProp = a\\\n\nint myOther = 42');
        assert.deepEqual(
            lines[1].flatMap((token) => token.scopes),
            [],
            'the empty line should no longer be inside the value'
        );
        assert.ok(
            lines[2].some((token) => token.scopes.some((s) => s.endsWith('storage.type.rwconfig'))),
            'the line after the empty line should be a new declaration'
        );
    });

    test('an unterminated allowed values list does not run past its line', async () => {
        // otherwise typing `int[` would break the highlighting of the whole file
        const lines = await tokenizeLines('int[0:100\nmyProp = fine');
        assert.ok(
            lines[1].some((token) =>
                token.scopes.some((s) => s.endsWith('variable.other.property-name.rwconfig'))
            ),
            'the next line should still be highlighted as a declaration'
        );
    });
});


describe('escape sequences in a value', () => {
    for (const escape of ['\\\\', '\\e', '\\n', '\\r', '\\t', '\\u0041']) {
        test(`${escape} is a valid escape`, async () => {
            const line = `string myProp = a${escape}b`;
            await assertValid(line);
            await assertScope(line, escape, 'constant.character.escape.rwconfig');
        });
    }

    for (const escape of ['\\q', '\\[', '\\u12']) {
        test(`${escape} is not a valid escape`, async () => {
            await assertInvalid(`string myProp = a${escape}b`);
        });
    }

    test('a `[` is an ordinary character in a value', async () => {
        await assertValid('string myProp = a[b');
        await assertScope('string myProp = a[b', 'a[b', 'string.unquoted.rwconfig');
    });

    test('an escaped comma is valid in a list item', async () => {
        const line = 'stringList myList = to be\\, or not to be, seize the day';
        await assertValid(line);
        await assertScope(line, '\\,', 'constant.character.escape.rwconfig');
    });
});


describe('an escaped space is only for the start of a value', () => {
    test('at the start of a value it is a valid escape', async () => {
        await assertValid('string myProp = \\ leading');
        await assertScope('string myProp = \\ leading', '\\ ', 'constant.character.escape.rwconfig');
    });

    test('at the start of each list item it is a valid escape', async () => {
        await assertValid('stringList myList = \\ a, \\ b');
    });

    test('at the start of an allowed value it is a valid escape', async () => {
        await assertValid('string[\\ a, b] myProp = b');
    });

    // the parser only warns about these - see ConfigFileTest.java. the grammar
    // is deliberately stricter, so that a misplaced escape is visible while it
    // is being typed
    test('later in a value it is marked invalid', async () => {
        await assertInvalid('string myProp = no\\ space');
        await assertScope('string myProp = no\\ space', '\\ ', 'invalid.illegal.escape.rwconfig');
    });

    test('later in a list item it is marked invalid', async () => {
        await assertInvalid('stringList myList = a, b\\ c');
    });

    test('a second escaped space is marked invalid', async () => {
        await assertInvalid('string myProp = \\ a\\ b');
    });

    test('an escaped backslash followed by a space is not an escaped space', async () => {
        await assertValid('string myProp = a\\\\ b');
    });
});


describe('escape sequences in an allowed values list', () => {
    for (const escape of ['\\\\', '\\]', '\\,', '\\:', '\\e', '\\n', '\\r', '\\t', '\\u0041']) {
        test(`${escape} is a valid escape`, async () => {
            const line = `string[a${escape}b, c] myProp = c`;
            await assertValid(line);
            await assertScope(line, escape, 'constant.character.escape.rwconfig');
        });
    }

    for (const escape of ['\\q', '\\[', '\\u12']) {
        test(`${escape} is not a valid escape`, async () => {
            await assertInvalid(`string[a${escape}b, c] myProp = c`);
        });
    }

    test('a `[` needs no escaping once the list is open', async () => {
        await assertValid('string[g[h, c] myProp = c');
    });
});


describe('allowed value ranges', () => {
    for (const range of ['0:100', '0:9, A:F', '80, 1024:65535', 'a\\:b, c', '\\e:z']) {
        test(`[${range}] is a well formed list`, async () => {
            await assertValid(`string[${range}] myProp = c`);
        });
    }

    test('a numeric range is highlighted as numbers', async () => {
        const line = 'intList[80, 1024:65535] myPorts = 1520, 8080';
        await assertScope(line, '1024', 'constant.numeric.rwconfig');
        await assertScope(line, '65535', 'constant.numeric.rwconfig');
        await assertScope(line, '8080', 'constant.numeric.rwconfig');
    });

    for (const range of ['0:10:20', 'a:b:c', '0:100:']) {
        test(`[${range}] has too many colons`, async () => {
            await assertInvalid(`string[${range}] myProp = c`);
        });
    }

    for (const range of [':100', '100:', ':z']) {
        test(`[${range}] is missing a bound`, async () => {
            await assertInvalid(`string[${range}] myProp = c`);
        });
    }

    test('only the malformed item of a list is marked', async () => {
        const invalid = await invalidTokens('int[0:100, 5:] myProp = 50');
        assert.deepEqual(invalid.map((token) => token.text), [' 5:']);
    });
});


describe('blank list items', () => {
    test('a string list may have blank items', async () => {
        await assertValid('stringList myList = a,,b');
        await assertValid('stringList myList = , a');
        await assertValid('stringList myList = a,');
    });

    test('a string type may have a blank allowed value', async () => {
        await assertValid('string[a,,b] myProp = a');
    });

    for (const line of [
        'intList myList = 1,,2',
        'intList myList = , 1',
        'intList myList = 1,',
        'doubleList myList = 1.0, , 2.0',
        'booleanList myList = true,',
    ]) {
        test(`${line} has a blank item in a non-string list`, async () => {
            await assertInvalid(line);
        });
    }

    for (const line of ['intList[1,,10] myList = 1', 'int[,5] myProp = 5', 'intList[1, 10,] myList = 1']) {
        test(`${line} has a blank allowed value for a non-string type`, async () => {
            await assertInvalid(line);
        });
    }

    test('a trailing comma before a line continuation is not a blank item', async () => {
        // the item may be on one of the lines that follow
        await assertValid('intList myList = 1, \\\n   2');
        await assertValid('intList myList = 1,\\\n2');
    });
});


describe('values are checked against their type', () => {
    test('numbers', async () => {
        await assertScope('int myProp = 42', '42', 'constant.numeric.rwconfig');
        await assertScope('long myProp = 5000000000', '5000000000', 'constant.numeric.rwconfig');
        await assertScope('double myProp = .314159e1', '.314159e1', 'constant.numeric.rwconfig');
        await assertInvalid('int myProp = 50.5');
        await assertInvalid('int myProp = notanumber');
    });

    test('booleans', async () => {
        for (const value of ['true', 'false', 'yes', 'no', 'on', 'off', '1', '0', 'TRUE']) {
            await assertScope(
                `boolean myProp = ${value}`,
                value,
                'constant.language.boolean.rwconfig'
            );
        }
        await assertInvalid('boolean myProp = maybe');
    });

    test('each item of a list is checked on its own', async () => {
        const invalid = await invalidTokens('booleanList myList = true, nope, false');
        assert.deepEqual(invalid.map((token) => token.text), ['nope']);
    });
});
