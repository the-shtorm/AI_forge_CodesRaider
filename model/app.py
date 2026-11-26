from fastapi import FastAPI

app = FastAPI(title="Smart Search Model")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "up"}
