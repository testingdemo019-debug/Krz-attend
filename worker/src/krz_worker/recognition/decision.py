"""The recognition decision for a confirmed crossing (R14), a pure function of the track's evidence and the active
threshold profile.

1. Liveness failed in at least 2 of the last 3 checks (camera with liveness on) -> LIVENESS_FAIL.
2. With face votes: the employee most of the last agreeN votes agree on (a vote agrees when its best match is that
   employee at or above accept - reviewBand; ties go to the higher score) is AUTO by FACE when its best score >= accept,
   the gap to the next employee in that vote >= margin and at least agreeK votes agree; at or above
   accept - reviewBand it is UNCERTAIN; below, UNKNOWN.
3. No usable face, OUT, Re-ID on: the same-day body gallery's best >= reidThreshold with the margin -> AUTO by REID,
   otherwise REID_UNCERTAIN.
4. No usable face otherwise -> UNKNOWN ("No face visible").
"""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field
from typing import Literal

from krz_worker.models.base import Liveness
from krz_worker.recognition.matcher import Match
from krz_worker.recognition.quality import GateThresholds

Hint = Literal["AUTO", "UNCERTAIN", "UNKNOWN", "LIVENESS_FAIL", "REID_UNCERTAIN"]
Method = Literal["FACE", "REID", "NONE"]
LivenessResult = Literal["PASSED", "FAILED", "SKIPPED"]


@dataclass(frozen=True)
class Thresholds:
    """The active threshold profile (``v_worker_recognition``)."""

    code: str
    accept: float
    review_band: float
    margin: float
    agree_k: int
    agree_n: int
    min_eye_px: int
    max_yaw: int
    sharp_min: int
    quality_min: int
    recheck_grow: int
    reid_on: bool
    reid_threshold: float

    @property
    def gate(self) -> GateThresholds:
        return GateThresholds(self.min_eye_px, self.max_yaw, self.sharp_min, self.quality_min)

    def band(self, score: float) -> str:
        """The overlay's confidence band, never the number: high, medium or low (as the backend's bands)."""
        if score >= max(0.75, self.accept + 0.10):
            return "high"
        return "medium" if score >= self.accept else "low"


@dataclass(frozen=True)
class Vote:
    """One recognition of the track's face: the best employees, highest first."""

    matches: tuple[Match, ...]

    @property
    def top(self) -> Match | None:
        return self.matches[0] if self.matches else None


@dataclass(frozen=True)
class Evidence:
    direction: Literal["IN", "OUT"]
    votes: tuple[Vote, ...] = ()
    #: The liveness checks of the track, oldest first (empty when the camera's liveness is off).
    liveness: tuple[Liveness, ...] = ()
    liveness_on: bool = False
    #: The same-day Re-ID gallery's best employees for the track's body (looked up only when ``needs_reid``).
    reid: tuple[Match, ...] = ()


@dataclass(frozen=True)
class Decision:
    hint: Hint
    method: Method
    liveness: LivenessResult
    liveness_score: float | None
    employee_id: str | None = None
    score: float | None = None
    second_score: float | None = None
    margin: float | None = None
    agree_k: int | None = None
    agree_n: int | None = None
    reid_score: float | None = None
    candidates: tuple[Match, ...] = field(default=())
    note: str | None = None


def needs_reid(evidence: Evidence, thresholds: Thresholds) -> bool:
    """Whether the decision will look at the Re-ID gallery (so Redis is only asked when it matters): an OUT whose
    face either was not seen or matched nobody (UNKNOWN)."""
    if evidence.direction != "OUT" or not thresholds.reid_on:
        return False
    if not evidence.votes:
        return True
    liveness, liveness_score = _liveness(evidence)
    return _face(evidence.votes, thresholds, liveness, liveness_score).hint == "UNKNOWN"


def decide(evidence: Evidence, thresholds: Thresholds) -> Decision:
    liveness, liveness_score = _liveness(evidence)
    if liveness == "FAILED":
        best = _best(evidence.votes)
        return Decision(
            "LIVENESS_FAIL",
            "NONE",
            liveness,
            liveness_score,
            employee_id=best.top.employee_id if best is not None and best.top is not None else None,
            candidates=best.matches[:3] if best is not None else (),
        )
    if evidence.votes:
        face = _face(evidence.votes, thresholds, liveness, liveness_score)
        # Turning back out within view: the last faces are the turn, often matching nobody. The back view then
        # decides, but only to name someone with certainty; a plausible face candidate (UNCERTAIN) still goes to review.
        if face.hint == "UNKNOWN" and evidence.direction == "OUT" and thresholds.reid_on and evidence.reid:
            reid = _reid(evidence.reid, thresholds, liveness, liveness_score)
            if reid.hint == "AUTO":
                return reid
        return face
    if evidence.direction == "OUT" and thresholds.reid_on:
        return _reid(evidence.reid, thresholds, liveness, liveness_score)
    return Decision("UNKNOWN", "NONE", liveness, liveness_score, note="No face visible")


def _face(
    votes: tuple[Vote, ...], thresholds: Thresholds, liveness: LivenessResult, liveness_score: float | None
) -> Decision:
    recent = votes[-thresholds.agree_n :]
    floor = thresholds.accept - thresholds.review_band
    tops = [v.top for v in recent if v.top is not None]
    agreeing = Counter(top.employee_id for top in tops if top.score >= floor)
    if agreeing:
        chosen = max(agreeing, key=lambda e: (agreeing[e], max(top.score for top in tops if top.employee_id == e)))
        best = _best(tuple(v for v in recent if v.top is not None and v.top.employee_id == chosen))
    else:
        best = _best(recent)
    if best is None or best.top is None:
        return Decision("UNKNOWN", "NONE", liveness, liveness_score, agree_n=len(recent))
    top = best.top
    second = next((m.score for m in best.matches[1:] if m.employee_id != top.employee_id), None)
    margin = round(top.score - (second if second is not None else 0.0), 4)
    agree_k = agreeing.get(top.employee_id, 0)
    if top.score < floor:
        return Decision("UNKNOWN", "NONE", liveness, liveness_score, score=top.score, agree_n=len(recent))
    accepted = top.score >= thresholds.accept and margin >= thresholds.margin and agree_k >= thresholds.agree_k
    return Decision(
        "AUTO" if accepted else "UNCERTAIN",
        "FACE" if accepted else "NONE",
        liveness,
        liveness_score,
        employee_id=top.employee_id,
        score=top.score,
        second_score=second,
        margin=margin,
        agree_k=agree_k,
        agree_n=len(recent),
        candidates=best.matches[:3],
    )


def _reid(
    matches: tuple[Match, ...], thresholds: Thresholds, liveness: LivenessResult, liveness_score: float | None
) -> Decision:
    if not matches:
        return Decision("REID_UNCERTAIN", "NONE", liveness, liveness_score, note="No same-day match for the back view")
    top = matches[0]
    margin = top.score - (matches[1].score if len(matches) > 1 else 0.0)
    accepted = top.score >= thresholds.reid_threshold and margin >= thresholds.margin
    return Decision(
        "AUTO" if accepted else "REID_UNCERTAIN",
        "REID" if accepted else "NONE",
        liveness,
        liveness_score,
        employee_id=top.employee_id,
        reid_score=top.score,
        candidates=matches[:3],
    )


def _liveness(evidence: Evidence) -> tuple[LivenessResult, float | None]:
    if not evidence.liveness_on or not evidence.liveness:
        return "SKIPPED", None
    last = evidence.liveness[-3:]
    failed = sum(1 for check in last if not check.passed)
    return ("FAILED" if failed >= 2 else "PASSED"), round(min(check.score for check in last), 4)


def _best(votes: tuple[Vote, ...]) -> Vote | None:
    scored = [(v.top.score, v) for v in votes if v.top is not None]
    return max(scored, key=lambda pair: pair[0])[1] if scored else None
