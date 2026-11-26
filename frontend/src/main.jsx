import { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import "./style.css";

function App() {
  const [health, setHealth] = useState(null);

  useEffect(() => {
    const controller = new AbortController();

    async function loadHealth() {
      try {
        const response = await fetch("/health", { signal: controller.signal, cache: "no-store" });
        if (!response.ok) throw new Error("Backend unavailable");
        setHealth(await response.json());
      } catch {
        if (!controller.signal.aborted) {
          setHealth({ status: "unavailable", model_service: "unknown" });
        }
      }
    }

    loadHealth();
    return () => controller.abort();
  }, []);

  const labels = { up: "Доступен", unavailable: "Недоступен", unknown: "Нет данных" };

  return (
    <main>
      <h1>Умный поиск Softline</h1>
      <h2>Состояние сервисов</h2>
      <dl aria-live="polite">
        <div><dt>Spring Boot</dt><dd>{health ? labels[health.status] : "Проверка…"}</dd></div>
        <div><dt>FastAPI</dt><dd>{health ? labels[health.model_service] : "Проверка…"}</dd></div>
      </dl>
    </main>
  );
}

createRoot(document.getElementById("root")).render(<App />);
