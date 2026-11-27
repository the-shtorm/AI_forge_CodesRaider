import { useEffect, useState } from "react";
import { api, safeLink } from "./api";

export default function ResultExplanation({ searchId, offerId }) {
  const [open, setOpen] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!open || result) return;
    const controller = new AbortController();
    api("/v1/explain", { body: { search_id: searchId, offer_id: offerId }, signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) setResult(value); })
      .catch(failure => { if (!controller.signal.aborted) setError(failure.message); });
    return () => controller.abort();
  }, [open, result, searchId, offerId]);

  const source = safeLink(result?.source_url);
  return <details className="proof" onToggle={event => {
    setOpen(event.currentTarget.open);
    if (event.currentTarget.open) setError("");
  }}>
    <summary>Почему в выдаче</summary>
    {result && <>
      <p>{result.text}</p>
      {source && <a href={source} target="_blank" rel="noreferrer">Источник</a>}
    </>}
    {error && <p role="alert">{error}</p>}
    {!result && !error && <p role="status">Загружаем…</p>}
  </details>;
}
