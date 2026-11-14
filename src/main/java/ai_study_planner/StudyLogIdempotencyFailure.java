package ai_study_planner;
public final class StudyLogIdempotencyFailure extends RuntimeException {
    public final int status; public final String code;
    public StudyLogIdempotencyFailure(int status,String code,String safeMessage){super(safeMessage);this.status=status;this.code=code;}
}
