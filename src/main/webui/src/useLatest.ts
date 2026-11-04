import { useEffect, useRef } from 'react';

/**
 * Wraps a page's requests so only the latest one settles: an older answer, or any answer after unmount, is dropped,
 * so a slow response never overwrites a newer one.
 */
export function useLatest() {
  const latest = useRef(0);
  useEffect(() => () => { latest.current++; }, []);
  return <T>(request: Promise<T>): Promise<T> => {
    const n = ++latest.current;
    // a dropped request never settles, so neither its success nor its failure handler runs
    return new Promise<T>((resolve, reject) => request.then(
      (v) => n === latest.current && resolve(v),
      (e: unknown) => n === latest.current && reject(e),
    ));
  };
}
