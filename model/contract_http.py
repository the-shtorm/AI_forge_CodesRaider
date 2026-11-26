"""Общие форматы ошибок публичного контракта и энкодера."""

from typing import Annotated

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.routing import APIRoute
from pydantic import BaseModel, ConfigDict, StringConstraints

Text = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, pattern=r"\S")]
PROBLEM_JSON = "application/problem+json"


class DTO(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Problem(DTO):
    """Ошибка в формате Problem Details. Клиент разбирает стабильный code."""

    type: str = "about:blank"
    title: str
    status: int
    detail: str
    code: str


class DocumentedAPI(FastAPI):
    def openapi(self):
        if self.openapi_schema is not None:
            return self.openapi_schema
        schema = super().openapi()
        schemas = schema.setdefault("components", {}).setdefault("schemas", {})
        schemas.setdefault("Problem", Problem.model_json_schema())
        # FastAPI removes nulls while serializing OpenAPI. In an example, null can be
        # the actual value of a required field such as price_minor or shortfall_reason.
        for route in self.routes:
            if not isinstance(route, APIRoute) or not route.include_in_schema:
                continue
            for status, response in route.responses.items():
                media = response.get("content", {}).get("application/json", {})
                examples = {key: media[key] for key in ("example", "examples") if key in media}
                if examples:
                    for method in route.methods:
                        operation = schema["paths"][route.path_format][method.lower()]
                        operation["responses"][str(status)]["content"]["application/json"].update(
                            examples
                        )
        return schema


def problem(status: int, code: str, title: str, detail: str) -> JSONResponse:
    body = Problem(title=title, status=status, code=code, detail=detail)
    return JSONResponse(body.model_dump(), status_code=status, media_type=PROBLEM_JSON)


def error_doc(description: str, **examples: tuple[int, str]) -> dict:
    media = {"schema": {"$ref": "#/components/schemas/Problem"}}
    if examples:
        media["examples"] = {
            code: {
                "value": Problem(
                    status=int(status), code=code, title=description, detail=detail
                ).model_dump()
            }
            for code, (status, detail) in examples.items()
        }
    return {"description": description, "content": {PROBLEM_JSON: media}}


def validation_handler(status: int):
    async def invalid_request(request: Request, exc: RequestValidationError):
        detail = ". ".join(
            f"{'.'.join(str(part) for part in error['loc'])} — {error['msg']}"
            for error in exc.errors()
        )
        return problem(status, "INVALID_REQUEST", "Некорректный запрос", detail)

    return invalid_request
