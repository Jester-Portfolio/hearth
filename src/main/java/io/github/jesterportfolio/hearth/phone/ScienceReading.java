package io.github.jesterportfolio.hearth.phone;

import java.util.*;

/** 공개 교육 자료를 검토해 직접 작성한 유한 노트. 게임 발견/연구 성과나 개인 데이터를 포함하지 않습니다. */
public enum ScienceReading {
    LEVER(ScienceCalculation.Topic.LEVER,"지렛대의 힘과 팔 길이","https://openstax.org/books/physics/pages/9-3-simple-machines",
        List.of("지렛대는 받침점을 중심으로 회전하는 막대다. 팔 길이는 받침점에서 힘을 가하는 지점까지의 거리다.",
            "막대와 수직인 힘이 정적 평형을 이룰 때, 필요한 힘 × 작용 팔 = 하중 × 하중 팔이다. 막대 자체 무게와 마찰은 제외한다.",
            "작용 팔이 길어지면 같은 하중을 지탱하는 힘은 작아진다. 하중 팔은 그대로 두고 작용 팔만 절반으로 줄이면 필요한 힘은 두 배가 된다.",
            "이상적인 단순 기계는 힘을 줄이는 대신 힘을 가하는 이동 거리를 늘린다. 같은 하중을 같은 높이로 옮기는 데 필요한 일이 저절로 줄지는 않는다.")),
    ENERGY(ScienceCalculation.Topic.ENERGY,"물체를 들어 올리는 에너지","https://openstax.org/books/college-physics/pages/7-3-gravitational-potential-energy",
        List.of("중력 위치 에너지는 높이의 기준점에 따라 달라진다. 여기서는 물체를 들어 올려 증가한 에너지만 다룬다.",
            "중력 가속도가 일정한 범위에서 에너지 증가 = 질량 × 중력 가속도 × 높이 증가이다.",
            "질량은 kg, 중력 가속도는 m/s², 높이 증가는 m로 입력하면 결과는 J이다. 중력 가속도는 조건으로 주어져야 하며 계산기에 직접 입력한다.",
            "같은 질량과 중력 가속도에서 높이 증가가 두 배이면 에너지 증가도 두 배다. 마찰 등 손실이 있다면 외부에서 필요한 에너지는 더 클 수 있다.")),
    SERIES(ScienceCalculation.Topic.SERIES,"직렬 연결한 저항","https://openstax.org/books/college-physics/pages/21-1-resistors-in-series-and-parallel",
        List.of("직렬 연결은 전류가 각 저항을 차례로 통과하는 하나의 경로를 이룬다. 각 저항에는 같은 전류가 흐른다.",
            "이상적인 두 저항을 직렬로 연결하면 합성 저항 = 첫 번째 저항 + 두 번째 저항이다. 각 저항과 결과의 단위는 Ω이다.",
            "저항이 추가되면 합성 저항은 작아지지 않는다. 온도 등으로 각 저항값이 달라지는 효과는 여기서 제외한다.",
            "이 합산 공식은 직렬 연결에 사용한다. 병렬 연결에는 적용하지 않으며, 현재 노트와 계산기는 병렬 합성 저항을 다루지 않는다."));
    private final ScienceCalculation.Topic calculation;private final String title,url;private final List<String> paragraphs;
    ScienceReading(ScienceCalculation.Topic calculation,String title,String url,List<String> paragraphs){this.calculation=calculation;this.title=title;this.url=url;this.paragraphs=List.copyOf(paragraphs);}
    public ScienceCalculation.Topic calculation(){return calculation;}public String title(){return title;}public String url(){return url;}public List<String> paragraphs(){return paragraphs;}
    public String context(){return "[자료 1] Hearth 과학 노트 · "+title+"\n"+String.join("\n",paragraphs)+"\n검토 출처: OpenStax · "+url;}
    public static boolean approvedContext(String context){return Arrays.stream(values()).anyMatch(note->note.context().equals(context));}
}
