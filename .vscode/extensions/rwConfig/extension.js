'use strict';

/**
 * Checks a project's Java code against its `rwconfig` file, and the `rwconfig`
 * file against itself.
 *
 * The checking is done by the `rwconfig-analyzer` jar rather than reimplemented
 * here, so that the editor and the Maven plugin can never disagree about what
 * is wrong. This file is the plumbing: find the jars, find the config file, run
 * the analyzer, turn what it says into diagnostics.
 *
 * Nothing here runs in a workspace the user has not trusted: checking spawns a
 * Java process, and which `java` that is can be set in configuration, so a
 * repository could otherwise choose the binary. `rwconfig.javaPath` is a
 * machine-scoped setting for the same reason.
 *
 * Config sources are never loaded automatically. Loading them means opening
 * whatever the file describes - an intranet URL, a database - and an editor
 * must not do that because someone typed. `rwConfig: Test config sources` does
 * it on request, and only then.
 */

const vscode = require('vscode');
const cp = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

let diagnostics;
let output;

/**
 * Which check is current. Every run takes a copy, and results from a run that
 * is no longer the current one are dropped: saving twice in quick succession
 * otherwise lands the first run's findings on top of the second's, and the two
 * sets are concatenated rather than replacing one another.
 */
let generation = 0;
let debounce;

/** Node's default is 1 MB, which a large project's findings can exceed. */
const MAX_OUTPUT = 32 * 1024 * 1024;

function enabled() {
    return vscode.workspace.getConfiguration('rwconfig').get('enable') !== false;
}

function activate(context) {
    diagnostics = vscode.languages.createDiagnosticCollection('rwconfig');
    output = vscode.window.createOutputChannel('rwConfig');
    context.subscriptions.push(diagnostics, output);

    context.subscriptions.push(
        vscode.commands.registerCommand('rwconfig.check', () => checkWorkspace(true)),
        vscode.commands.registerCommand('rwconfig.testSources', testSources)
    );

    // re-check when something that could change the answer is saved. A whole
    // JVM starts per run, so a burst of saves waits for the burst to finish
    // rather than starting a process for each one.
    context.subscriptions.push(
        vscode.workspace.onDidSaveTextDocument((document) => {
            if (document.languageId === 'java' || path.basename(document.fileName) === 'rwconfig') {
                clearTimeout(debounce);
                debounce = setTimeout(() => checkWorkspace(false), 400);
            }
        }),
        new vscode.Disposable(() => clearTimeout(debounce))
    );
    checkWorkspace(false);
}

/**
 * Every folder we are checking. A multi-root workspace has more than one, and
 * each is a project in its own right - its own config files, its own jars.
 */
function workspaceRoots() {
    const folders = vscode.workspace.workspaceFolders;
    return folders ? folders.map((f) => f.uri.fsPath) : [];
}

/** Directories that never hold a project's own `rwconfig`. */
const IGNORED_DIRECTORIES = new Set(['target', 'build', 'node_modules', '.git', '.vscode', 'bin', 'out']);

/**
 * Every `rwconfig` file in the workspace.
 *
 * A single-module project keeps one at the root, but a multi-module build has
 * one per application, so searching is the only thing that works in both. Build
 * output is skipped, or a copied resource would be checked twice.
 */
function findConfigFiles(root) {
    const configured = vscode.workspace.getConfiguration('rwconfig').get('file');
    if (configured) {
        const resolved = path.isAbsolute(configured) ? configured : path.join(root, configured);
        return fs.existsSync(resolved) ? [resolved] : [];
    }
    const found = [];
    const walk = (dir, depth) => {
        if (depth > 6) {
            return;
        }
        let entries;
        try {
            entries = fs.readdirSync(dir, { withFileTypes: true });
        } catch (e) {
            return;
        }
        for (const entry of entries) {
            const full = path.join(dir, entry.name);
            if (entry.isDirectory()) {
                if (!IGNORED_DIRECTORIES.has(entry.name) && !entry.name.startsWith('.')) {
                    walk(full, depth + 1);
                }
            } else if (entry.name === 'rwconfig') {
                found.push(full);
            }
        }
    };
    walk(root, 0);
    return found;
}

/**
 * The Java sources that go with a config file: the module it belongs to, found
 * by walking up out of `src/main/resources`. Falls back to the workspace.
 */
function sourceRootsFor(configFile, root) {
    const configured = vscode.workspace.getConfiguration('rwconfig').get('sourceRoots');
    if (configured && configured.length > 0) {
        return configured
            .map((r) => (path.isAbsolute(r) ? r : path.join(root, r)))
            .filter((r) => fs.existsSync(r));
    }
    let dir = path.dirname(configFile);
    if (dir.endsWith(path.join('src', 'main', 'resources'))) {
        dir = path.resolve(dir, '..', '..', '..');
    }
    const moduleSources = path.join(dir, 'src', 'main', 'java');
    if (fs.existsSync(moduleSources)) {
        return [moduleSources];
    }
    return fs.existsSync(path.join(root, 'src', 'main', 'java'))
        ? [path.join(root, 'src', 'main', 'java')]
        : [];
}

/**
 * The analyzer's classpath. The jars ship with the extension, but a project's
 * own copy wins when there is one: it knows the file format of the version the
 * project actually uses, which the bundled copy may predate.
 */
function classpath(root) {
    // Project jars first so they win on duplicate classes, then the bundled
    // ones - which supply the third-party pieces (slf4j) that a project's
    // `target` directories do not contain.
    const jars = findProjectJars(root).concat(bundledJars());
    return jars.length > 0 ? jars.join(path.delimiter) : undefined;
}

function bundledJars() {
    const bundled = path.join(__dirname, 'lib');
    if (!fs.existsSync(bundled)) {
        return [];
    }
    return fs.readdirSync(bundled)
        .filter((f) => f.endsWith('.jar'))
        .map((f) => path.join(bundled, f));
}

/**
 * Analyzer jars built inside the project itself, if this is that project. They
 * take precedence over the bundled ones because they understand the file
 * format of the version being worked on, which the bundled copy may predate.
 */
function findProjectJars(root) {
    const jars = [];
    for (const module of ['rwconfig-analyzer', 'config', 'plugin-api']) {
        const dir = path.join(root, module, 'target');
        if (!fs.existsSync(dir)) {
            continue;
        }
        const jar = fs.readdirSync(dir).find((f) => f.endsWith('.jar') && !f.includes('sources'));
        if (jar) {
            jars.push(path.join(dir, jar));
        }
    }
    return jars;
}

function javaCommand() {
    return vscode.workspace.getConfiguration('rwconfig').get('javaPath') || 'java';
}

/** Run the analyzer and turn what it says into diagnostics. */
function checkWorkspace(announce) {
    if (!announce && !enabled()) {
        return;
    }
    // Anything still in flight from an earlier save is now answering a question
    // about code that has since changed.
    const mine = ++generation;

    const jobs = [];
    for (const root of workspaceRoots()) {
        for (const config of findConfigFiles(root)) {
            jobs.push({ root, config });
        }
    }
    if (jobs.length === 0) {
        diagnostics.clear();
        if (announce) {
            vscode.window.showInformationMessage('rwConfig: no `rwconfig` file in this workspace.');
        }
        return;
    }

    const collected = new Map();
    const failures = [];
    let pending = jobs.length;
    let total = 0;

    for (const { root, config } of jobs) {
        const cpath = classpath(root);
        if (!cpath) {
            failures.push(
                'the analyzer is not available, so only syntax highlighting is active. '
                + 'An installed extension ships with it; when working in the rwConfig repo itself, '
                + 'run `.vscode/extensions/rwConfig/package-jars.sh` once to build it.');
            if (--pending === 0) {
                finish(mine, collected, failures, total, jobs.length, announce);
            }
            continue;
        }
        const args = ['-cp', cpath, 'net.rabbitware.config.analyzer.Main',
                      'check', '--rwconfig', config];
        for (const source of sourceRootsFor(config, root)) {
            args.push('--source', source);
        }
        cp.execFile(
            javaCommand(), args, { cwd: root, maxBuffer: MAX_OUTPUT },
            (error, stdout, stderr) => {
                if (error && !stdout) {
                    // A run that could not produce an answer is a failure, not a clean result -
                    // reporting "nothing to report" here would be reporting a broken Java
                    // installation as a healthy project.
                    failures.push('could not run the analyzer for ' + path.relative(root, config)
                        + ' - ' + ((stderr || '').trim() || error.message));
                } else {
                    if (error) {
                        failures.push('the analyzer exited abnormally for '
                            + path.relative(root, config) + ' - results may be incomplete.');
                    }
                    total += collectFindings(stdout, root, collected);
                }
                if (--pending === 0) {
                    finish(mine, collected, failures, total, jobs.length, announce);
                }
            }
        );
    }
}

/**
 * Publish what the runs found, unless a newer check has started in the meantime.
 * Diagnostics are replaced in one go rather than cleared up front, so a failed
 * run leaves the previous answer standing instead of a misleading empty panel.
 */
function finish(mine, collected, failures, total, fileCount, announce) {
    if (mine !== generation) {
        return;
    }
    diagnostics.clear();
    for (const [file, list] of collected) {
        diagnostics.set(vscode.Uri.file(file), list);
    }
    for (const failure of failures) {
        output.appendLine('rwConfig: ' + failure);
    }
    if (failures.length > 0 && announce) {
        output.show(true);
        vscode.window.showErrorMessage('rwConfig: the check did not complete. See the rwConfig output.');
        return;
    }
    if (announce) {
        vscode.window.showInformationMessage(
            total === 0
                ? 'rwConfig: checked ' + fileCount
                  + (fileCount === 1 ? ' file' : ' files') + ', nothing to report.'
                : 'rwConfig: ' + total + (total === 1 ? ' finding.' : ' findings.')
        );
    }
}

/** Collect the findings from one analyzer run. Returns how many there were. */
function collectFindings(stdout, workspaceRoot, into) {
    const skipped = new Set(vscode.workspace.getConfiguration('rwconfig').get('skipRules') || []);
    let count = 0;
    for (const line of stdout.split('\n')) {
        if (!line.trim().startsWith('{')) {
            continue;
        }
        let finding;
        try {
            finding = JSON.parse(line);
        } catch (e) {
            continue;
        }
        if (skipped.has(finding.rule)) {
            continue;
        }
        count++;
        const file = path.isAbsolute(finding.file) ? finding.file : path.resolve(workspaceRoot, finding.file);
        const list = into.get(file) || [];
        // the analyzer counts from 1, VS Code from 0. A finding with a length
        // underlines that span - the declared name, or the quoted name in a
        // call - and one without underlines the first character.
        const line0 = Math.max(0, (finding.line || 1) - 1);
        const col0 = Math.max(0, (finding.column || 1) - 1);
        const length = finding.length > 0 ? finding.length : 1;
        const range = new vscode.Range(line0, col0, line0, col0 + length);
        const diagnostic = new vscode.Diagnostic(range, finding.message, severityOf(finding.severity));
        diagnostic.source = 'rwconfig';
        diagnostic.code = finding.rule;
        list.push(diagnostic);
        into.set(file, list);
    }
    return count;
}

function severityOf(name) {
    switch (name) {
        case 'ERROR': return vscode.DiagnosticSeverity.Error;
        case 'WARNING': return vscode.DiagnosticSeverity.Warning;
        default: return vscode.DiagnosticSeverity.Information;
    }
}

/**
 * Load every config source, exactly as the application would. This opens
 * whatever the file describes, which is why it only ever happens when someone
 * asks for it.
 */
function testSources() {
    const jobs = [];
    for (const root of workspaceRoots()) {
        for (const config of findConfigFiles(root)) {
            jobs.push({ root, config });
        }
    }
    if (jobs.length === 0) {
        vscode.window.showInformationMessage('rwConfig: no `rwconfig` file in this workspace.');
        return;
    }
    if (jobs.length === 1) {
        loadSources(jobs[0].root, jobs[0].config);
        return;
    }
    const labels = jobs.map((job) => path.relative(job.root, job.config));
    vscode.window.showQuickPick(labels, {
        title: 'Which configuration should be loaded?'
    }).then((picked) => {
        const job = jobs[labels.indexOf(picked)];
        if (job) {
            loadSources(job.root, job.config);
        }
    });
}

/** Actually load every source of one config file. */
function loadSources(root, config) {
    const cpath = classpath(root);
    if (!cpath) {
        vscode.window.showErrorMessage(
            'rwConfig: the analyzer is not available. Run `package-jars.sh` in the extension folder.');
        return;
    }
    vscode.window.withProgress(
        { location: vscode.ProgressLocation.Notification, title: 'rwConfig: loading config sources...' },
        () => new Promise((resolve) => {
            cp.execFile(
                javaCommand(),
                ['-cp', cpath, 'net.rabbitware.config.analyzer.Main', 'test-sources', '--rwconfig', config],
                { cwd: root, maxBuffer: MAX_OUTPUT },
                (error, stdout, stderr) => {
                    let result;
                    try {
                        result = JSON.parse((stdout || '').split('\n').find((l) => l.startsWith('{')) || '{}');
                    } catch (e) {
                        result = {};
                    }
                    if (result.ok) {
                        vscode.window.showInformationMessage(
                            'rwConfig: every source loaded - ' + result.properties + ' properties.');
                    } else if (result.message) {
                        output.appendLine('rwConfig: ' + result.message);
                        output.show(true);
                        vscode.window.showWarningMessage(
                            'rwConfig: the sources could not all be loaded here. See the rwConfig output.');
                    } else {
                        output.appendLine('rwConfig: ' + (stderr || (error && error.message) || 'no result'));
                        output.show(true);
                        vscode.window.showErrorMessage('rwConfig: could not run the check.');
                    }
                    resolve();
                }
            );
        })
    );
}

function deactivate() { }

module.exports = { activate, deactivate };
