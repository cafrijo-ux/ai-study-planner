package ai_study_planner;
public final class AiCostFailure extends RuntimeException {
    public final int status; public final String code;
    public AiCostFailure(int status,String code,String message){super(message);this.status=status;this.code=code;}
}
