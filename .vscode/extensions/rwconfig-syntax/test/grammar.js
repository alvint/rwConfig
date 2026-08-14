'use strict';

/**
 * Test helpers for the rwconfig TextMate grammar.
 *
 * These run the grammar through the same libraries VS Code itself uses, so the
 * scopes here are the scopes an editor would apply.
 */

const fs = require('node:fs');
const path = require('node:path');
const oniguruma = require('vscode-oniguruma');
const vsctm = require('vscode-textmate');

const SCOPE_NAME = 'source.rwconfig';
const GRAMMAR_PATH = path.join(__dirname, '..', 'syntaxes', 'rwconfig.tmLanguage.json');

let grammarPromise;

/** Load (once) the grammar under test. */
function loadGrammar() {
    if (!grammarPromise) {
        grammarPromise = (async () => {
            const wasm = fs.readFileSync(require.resolve('vscode-oniguruma/release/onig.wasm'));
            await oniguruma.loadWASM(
                wasm.buffer.slice(wasm.byteOffset, wasm.byteOffset + wasm.byteLength)
            );
            const registry = new vsctm.Registry({
                onigLib: Promise.resolve({
                    createOnigScanner: (patterns) => new oniguruma.OnigScanner(patterns),
                    createOnigString: (text) => new oniguruma.OnigString(text),
                }),
                loadGrammar: async (scopeName) =>
                    scopeName === SCOPE_NAME
                        ? vsctm.parseRawGrammar(fs.readFileSync(GRAMMAR_PATH, 'utf8'), GRAMMAR_PATH)
                        : null,
            });
            const grammar = await registry.loadGrammar(SCOPE_NAME);
            if (!grammar) {
                throw new Error(`could not load the grammar for ${SCOPE_NAME}`);
            }
            return grammar;
        })();
    }
    return grammarPromise;
}

/**
 * Tokenize some config text. Returns one array of `{text, scopes}` tokens per
 * line, with the root scope left out since every token carries it.
 *
 * Tokenizing line by line - carrying the rule stack forward - is what makes
 * multi-line behavior (line joining, comments) testable.
 */
async function tokenizeLines(text) {
    const grammar = await loadGrammar();
    let ruleStack = vsctm.INITIAL;
    return text.split('\n').map((line) => {
        const result = grammar.tokenizeLine(line, ruleStack);
        ruleStack = result.ruleStack;
        return result.tokens.map((token) => ({
            text: line.substring(token.startIndex, token.endIndex),
            scopes: token.scopes.filter((scope) => scope !== SCOPE_NAME),
        }));
    });
}

/** Tokenize some config text into a single flat list of tokens. */
async function tokenize(text) {
    return (await tokenizeLines(text)).flat();
}

/**
 * The scopes applied to `substring` in `text`. Adjacent tokens that share a
 * scope are merged first, so a caller can ask about a whole value without
 * caring where the grammar happened to split it.
 */
async function scopesOf(text, substring) {
    const merged = [];
    for (const token of await tokenize(text)) {
        const previous = merged[merged.length - 1];
        if (previous && String(previous.scopes) === String(token.scopes)) {
            previous.text += token.text;
        } else {
            merged.push({ ...token });
        }
    }
    const match = merged.find((token) => token.text === substring);
    if (!match) {
        throw new Error(
            `no token for ${JSON.stringify(substring)} in:\n` +
            merged.map((t) => `  ${JSON.stringify(t.text)} <${t.scopes.join(', ')}>`).join('\n')
        );
    }
    return match.scopes;
}

/** Every token in `text` that the grammar marked as invalid. */
async function invalidTokens(text) {
    return (await tokenize(text)).filter((token) =>
        token.scopes.some((scope) => scope.startsWith('invalid.illegal'))
    );
}

/** Whether the grammar marked anything in `text` as invalid. */
async function hasInvalid(text) {
    return (await invalidTokens(text)).length > 0;
}

module.exports = { loadGrammar, tokenize, tokenizeLines, scopesOf, invalidTokens, hasInvalid };
