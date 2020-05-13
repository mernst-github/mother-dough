export function sleep(delay: number, signal?: AbortSignal) {
    checkAbort(signal);
    return new Promise((resolve, reject) => {
        let timer;
        const listener = () => {
            clearTimeout(timer);
            reject(signal);
        };
        timer = setTimeout(() => {
            if (signal) signal.removeEventListener('abort', listener);
            resolve();
        }, delay);
        if (signal) signal.addEventListener('abort', listener);
        return timer;
    });
}

export async function after<T>(
    delay: number,
    starter: (AbortSignal?) => Promise<T>,
    signal?) {
    await sleep(delay, signal);
    return starter(signal);
}

export async function retry<T>(starter: (AbortSignal) => Promise<T>,
                               delays: Iterable<number>,
                               signal?: AbortSignal): Promise<T> {
    let lastError;
    try {
        return await starter(signal);
    } catch (e) {
        lastError = e;
    }
    for (const delay of delays) {
        await sleep(delay, signal);
        try {
            return await starter(signal);
        } catch (e) {
            lastError = e;
        }
    }
    throw lastError;
}

export async function hedge<T>(starter: (AbortSignal?) => Promise<T>, delay: number, signal?) {
    let lastError;
    for await (const [p] of parallel(function* (signal) {
        yield starter(signal);
        yield after(delay, starter, signal);
    }, 2, signal)) {
        try {
            return await p;
        } catch (e) {
            lastError = e;
        }
    }
    throw lastError;
}

export async function* parallel<T>(
    starter: (signal: AbortSignal) => Iterator<Promise<T>>,
    parallelism: number,
    signal?: AbortSignal) {
    const childController = new AbortController();
    const childListener = () => childController.abort();
    if (signal) {
        signal.addEventListener('abort', childListener);
    }
    try {
        const remaining: Promise<T>[] = [];
        const it = starter(childController.signal);
        while (true) {
            while (remaining.length < parallelism) {
                const next = it.next();
                if (next.done) break;
                remaining.push(next.value);
            }
            if (!remaining.length) {
                break;
            }
            const index: number = await Promise.race(remaining.map((p, i) => p.then(() => i, () => i)));
            yield remaining.splice(index, 1);
        }
    } finally {
        if (signal) {
            signal.removeEventListener('abort', childListener);
        }
        childController.abort();
    }
}

export async function collect<T, U>(
    starter: (signal: AbortSignal) => Iterator<Promise<T>>,
    parallelism: number,
    zero: U,
    accumulate: (U, T) => U,
    terminate: (U) => boolean,
    ignore: (any) => boolean,
    postpone: (any) => boolean,
    signal?: AbortSignal) {
    let acc = zero;
    let postponed;
    for await (const [p] of parallel(starter, parallelism, signal)) {
        let v;
        try {
            v = await p;
        } catch (e) {
            checkAbort(signal);
            if (ignore(e)) {
                continue;
            }
            if (!postponed && postpone(e)) {
                postponed = e;
                continue;
            }
            throw e;
        }
        acc = accumulate(acc, v);
        if (terminate(acc)) {
            return acc;
        }
    }
    if (postponed) {
        throw postponed;
    }
    return acc;
}

function checkAbort(signal: AbortSignal) {
    if (signal && signal.aborted) {
        throw signal;
    }
}
