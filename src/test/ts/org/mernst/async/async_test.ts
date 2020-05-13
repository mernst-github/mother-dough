import {after, hedge, parallel, retry, sleep} from '../../../../../main/ts/org/mernst/async/async';

describe("sleep", () => {
    it("actually waits the time you specify", async () => {
        await expectTakes(500, async () => await sleep(500));
    });
    it("can be aborted", async () => {
        const sut = expectTakes(100, abortedAfter(100, async (signal) => await sleep(500, signal)));
        let caught;
        try {
            await sut;
        } catch (e) {
            caught = e;
        }
        expect(caught).toBeInstanceOf(AbortSignal);
    });
});

describe("after", () => {
    it("works", async () => {
        let x = undefined;
        await expectTakes(500, async () => {
            await after(500, async () => {
                x = 3
            });
        });
        expect(x).toBe(3);
    });
    it("is stopped by abort", async () => {
        let x;
        let caught;
        try {
            await expectTakes(100,
                abortedAfter(100,
                    async (signal) => await after(500, async () => {
                        x = 3
                    }, signal)));
        } catch (e) {
            caught = e;
        }
        expect(caught).toBeInstanceOf(AbortSignal);
        expect(x).toBeUndefined();
    });
});

describe("retry", () => {
    it("won't on success", async () => {
        let attempts = 0;
        const result = await retry(async () => {
            ++attempts;
            return 3;
        }, [500]);
        expect(result).toBe(3);
        expect(attempts).toBe(1);
    });
    it("retries until success", async () => {
        let attempts = 0;
        let result = undefined;
        result = await retry(async () => {
            if (++attempts == 3) {
                return 3;
            }
            throw 'e';
        }, [100, 100, 100]);
        expect(result).toBe(3);
        expect(attempts).toBe(3);
    });
    it("throws last error", async () => {
        let attempts = 0;
        let error = undefined;
        try {
            await retry(async () => {
                throw ++attempts;
            }, [100, 100, 100]);
        } catch (e) {
            error = e;
        }
        expect(error).toBe(4);
        expect(attempts).toBe(4);
    });
    it("actually sleeps", async () => {
        await expectTakes(500, async () => {
            try {
                await retry(async () => {
                    throw 'e';
                }, [100, 100, 100, 100, 100]);
            } catch (e) {
            }
        });
    });
});
describe("hedging", () => {
    it('starts both and returns faster one', async () => {
        let attempts = 0;
        let result;
        await expectTakes(300, async () => {
            result = await hedge(async () => {
                ++attempts;
                await sleep((attempts == 1) ? 500 : 200);
                return attempts;
            }, 100);
        });
        expect(result).toBe(2);
        expect(attempts).toBe(2);
    });
    it('never starts the second', async () => {
        let attempts = 0;
        await expectTakes(200, async () => {
            const result = await hedge(async () => {
                ++attempts;
                await sleep(200);
                return attempts;
            }, 300);
            expect(result).toBe(1);
        });
        // no better way to check whether backup attempt is started.
        await sleep(300);
        expect(attempts).toBe(1);
    });
});
describe("parallel", () => {
    const parallelize = (started?, finished?) => parallel(function* (signal) {
        for (const i of [1, 2, 3, 4, 5]) {
            if (started) started.push(i);
            yield (async () => {
                await sleep(100, signal);
                finished.push(i);
                return i;
            })();
        }
    }, 3);

    it("parallelizes", async () => {
        let started = [];
        const sut = parallelize(started);
        await sut.next();
        expect(started).toEqual([1, 2, 3]);
    });
    it("returns finished promises", async () => {
        let finished = [];
        const sut = parallelize(null, finished);
        const result = await sut.next();
        expect(finished.length).toBeGreaterThan(0);
        expect(result.done).toBeFalsy();
        // @ts-ignore
        expect(result.value.length).toBe(1);
        expect(finished.indexOf(await result.value[0])).toBeGreaterThanOrEqual(0);
    });
    it("keeps parallelism up", async () => {
        let started = [];
        const sut = parallelize(started);
        await sut.next();
        await sut.next();
        await sleep(200);
        expect(started).toEqual([1, 2, 3, 4]);
    });
    it("can handle failures", async () => {
        const result = await (parallel(function* () {
            yield Promise.reject('error');
        }, 3).next());
        await (result.value[0].then(v => {
            throw `Unexpected ${v}`;
        }, e => {
            expect(e).toBe('error');
        }));
    });
    it("aborts pre-started work", async () => {
        let started = [];
        let finished = [];
        const sut = parallelize(started, finished);
        await sut.next();
        await sut.return();
        await sleep(500);
        expect(started).toEqual([1, 2, 3]);
        expect(finished).toEqual([1]);
    });
});

async function expectTakes<T>(millis: number, body: (signal) => Promise<T>, signal?): Promise<T> {
    const p = body(signal);
    const start = Date.now();
    try {
        await p;
    } catch (e) {
    }
    const end = Date.now();
    expect(Math.abs(end - start - millis)).toBeLessThan(50, `Took ${end - start} instead of ${millis}`);
    return await p;
}

function abortedAfter<T>(millis: number, body: (signal) => Promise<T>): (signal) => Promise<T> {
    return async () => {
        const c = new AbortController();
        const p = body(c.signal);
        await sleep(millis);
        c.abort();
        return await p;
    };
}

// this makes us a proper module
export {};
