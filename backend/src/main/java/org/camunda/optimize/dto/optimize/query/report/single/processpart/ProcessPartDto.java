package org.camunda.optimize.dto.optimize.query.report.single.processpart;

public class ProcessPartDto {

  protected String start;
  protected String end;

  public String getStart() {
    return start;
  }

  public void setStart(String start) {
    this.start = start;
  }

  public String getEnd() {
    return end;
  }

  public void setEnd(String end) {
    this.end = end;
  }

  public String createCommandKey() {
    return "processPart";
  }
}
