package cal;

public class Entry {

  String hour;
  String description;
  String color;

  public Entry (String hour) {
    this.hour = hour;
    this.description = "";

  }

  public String getHour () {
    return this.hour;
  }

  public String getColor () {
    if (description.equals("")) return "lightblue";
    else return "red";
  }

  public String getDescription () {
    if (description.equals("")) return "None";
    else return this.description;
  }

  public void setDescription (String descr) {
    description = descr;
  }
 
}





