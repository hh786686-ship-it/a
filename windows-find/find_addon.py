"""Search/navigation extension for Pocket Highlighter Windows."""
import re
import tkinter as tk
from tkinter import ttk


def find_matches(text, query, limit=2000):
    if not query: return []
    return [(m.start(),m.end()) for _,m in zip(range(limit),re.finditer(re.escape(query),text,re.I))]


def summary(text, start, end):
    a=max(0,start-35); b=min(len(text),end+35)
    return ('…' if a else '')+' '.join(text[a:b].split())+('…' if b<len(text) else '')


def install(App):
    old_ui=App._build_ui
    old_render=App.render
    old_load=App.load
    old_change=App.text_changed

    def build_ui(self):
        self.find_query=tk.StringVar()
        self.find_hits=[];self.find_cursor=-1;self.find_timer=None
        old_ui(self)
        pane=self.find_pane=ttk.Frame(self.main,padding=8,width=275)
        pane.pack_propagate(False)
        heading=ttk.Frame(pane);heading.pack(fill='x',pady=(2,8))
        ttk.Label(heading,text='Navigation',font=('Segoe UI',16)).pack(side='left')
        ttk.Button(heading,text='✕',width=3,command=self.hide_find).pack(side='right')
        self.find_entry=ttk.Entry(pane,textvariable=self.find_query)
        self.find_entry.pack(fill='x',pady=(0,8))
        self.find_query.trace_add('write',lambda *_:self.schedule_find())
        line=ttk.Frame(pane);line.pack(fill='x')
        self.find_count=tk.StringVar(value='Type to search')
        ttk.Label(line,textvariable=self.find_count).pack(side='left')
        ttk.Button(line,text='▼',width=3,command=lambda:self.find_next(1)).pack(side='right')
        ttk.Button(line,text='▲',width=3,command=lambda:self.find_next(-1)).pack(side='right')
        ttk.Separator(pane).pack(fill='x',pady=8)
        ttk.Label(pane,text='Results',font=('Segoe UI',10,'bold')).pack(anchor='w')
        frame=ttk.Frame(pane);frame.pack(fill='both',expand=True,pady=(6,0))
        self.find_list=tk.Listbox(frame,activestyle='none',font=('Segoe UI',10),exportselection=False)
        scrollbar=ttk.Scrollbar(frame,command=self.find_list.yview)
        self.find_list.configure(yscrollcommand=scrollbar.set)
        scrollbar.pack(side='right',fill='y');self.find_list.pack(side='left',fill='both',expand=True)
        self.find_list.bind('<<ListboxSelect>>',self.find_select)
        self.find_entry.bind('<Return>',lambda e:self.find_next(1))
        self.find_entry.bind('<Escape>',self.hide_find)
        self.find_list.bind('<Escape>',self.hide_find)
        self.doc_text.tag_configure('found',background='#fff2a8')
        self.doc_text.tag_configure('found_current',background='#f9aa47')
        self.root.bind_all('<Control-f>',self.ctrl_find)
        self.root.bind_all('<F3>',lambda e:self.find_next(1))
        self.root.bind_all('<Shift-F3>',lambda e:self.find_next(-1))

    def ctrl_find(self,event=None):
        if not self.find_pane.winfo_manager():
            self.find_pane.pack(side='left',fill='y',before=self.viewer)
        self.find_entry.focus_set();self.find_entry.selection_range(0,'end')
        self.search_now()
        return 'break'

    def hide_find(self,event=None):
        self.find_pane.pack_forget()
        self.doc_text.tag_remove('found','1.0','end')
        self.doc_text.tag_remove('found_current','1.0','end')
        self.canvas.delete('find_overlay')
        return 'break'

    def schedule_find(self):
        if self.find_timer is not None:self.root.after_cancel(self.find_timer)
        self.find_timer=self.root.after(140,self.search_now)

    def search_now(self):
        if self.find_timer is not None:
            self.root.after_cancel(self.find_timer);self.find_timer=None
        self.find_hits=[];self.find_cursor=-1;self.find_list.delete(0,'end')
        self.doc_text.tag_remove('found','1.0','end')
        self.doc_text.tag_remove('found_current','1.0','end')
        q=self.find_query.get().strip()
        if not q:
            self.find_count.set('Type to search');self.canvas.delete('find_overlay');return
        if self.mode=='docx':
            content=self.doc_text.get('1.0','end-1c')
            for a,b in find_matches(content,q):
                self.find_hits.append(dict(page=0,start=a,end=b,label=summary(content,a,b)))
        elif self.mode=='pdf' and self.pdf is not None:
            for number in range(self.page_count):
                page=self.pdf.load_page(number)
                excerpt=' '.join(page.get_text().split())[:100]
                for rect in page.search_for(q):
                    self.find_hits.append(dict(page=number,rect=tuple(rect),label=f'Page {number+1} · {excerpt}'))
                    if len(self.find_hits)>=2000:break
                if len(self.find_hits)>=2000:break
        elif self.mode=='image':
            self.find_count.set('Images have no searchable text');self.canvas.delete('find_overlay');return
        else:
            self.find_count.set('Open a PDF or DOCX to search');return
        for hit in self.find_hits:self.find_list.insert('end',hit['label'])
        self.find_count.set(f'{len(self.find_hits)} results')
        if self.find_hits:self.find_show(0)
        elif self.mode=='pdf':self.render()

    def find_select(self,event=None):
        index=self.find_list.curselection()
        if index and index[0]!=self.find_cursor:self.find_show(index[0])

    def find_next(self,direction=1):
        if not self.find_pane.winfo_manager():self.ctrl_find()
        if self.find_hits:self.find_show((self.find_cursor+direction)%len(self.find_hits))
        return 'break'

    def find_show(self,index):
        if index<0 or index>=len(self.find_hits):return
        self.find_cursor=index;hit=self.find_hits[index]
        self.find_list.selection_clear(0,'end');self.find_list.selection_set(index);self.find_list.see(index)
        self.find_count.set(f'{index+1} of {len(self.find_hits)} results')
        if self.mode=='docx':
            self.doc_text.tag_remove('found','1.0','end')
            self.doc_text.tag_remove('found_current','1.0','end')
            for h in self.find_hits:
                self.doc_text.tag_add('found',f"1.0+{h['start']}c",f"1.0+{h['end']}c")
            a=f"1.0+{hit['start']}c";b=f"1.0+{hit['end']}c"
            self.doc_text.tag_add('found_current',a,b)
            self.doc_text.tag_raise('found');self.doc_text.tag_raise('found_current')
            self.doc_text.see(a)
        elif self.mode=='pdf':
            self.page=hit['page'];x0,y0,x1,y1=hit['rect']
            self.offset=[self.canvas.winfo_width()/2-(x0+x1)*self.zoom/2,
                         self.canvas.winfo_height()/2-(y0+y1)*self.zoom/2]
            self.render()

    def paint_find(self):
        self.canvas.delete('find_overlay')
        if self.mode!='pdf' or not self.find_pane.winfo_manager():return
        for i,h in enumerate(self.find_hits):
            if h['page']!=self.page:continue
            x0,y0,x1,y1=h['rect']
            coords=(self.offset[0]+x0*self.zoom,self.offset[1]+y0*self.zoom,
                    self.offset[0]+x1*self.zoom,self.offset[1]+y1*self.zoom)
            self.canvas.create_rectangle(*coords,fill='#ffc966' if i==self.find_cursor else '#fff0a0',
                outline='#d67308' if i==self.find_cursor else '#e9b63e',
                width=3 if i==self.find_cursor else 1,stipple='gray25',tags='find_overlay')

    def render(self):
        old_render(self)
        self.paint_find()

    def load(self,path):
        old_load(self,path)
        if self.find_pane.winfo_manager():self.search_now()

    def text_changed(self,event):
        old_change(self,event)
        if self.mode=='docx' and self.find_pane.winfo_manager():self.schedule_find()

    App._build_ui=build_ui
    App.ctrl_find=ctrl_find
    App.hide_find=hide_find
    App.schedule_find=schedule_find
    App.search_now=search_now
    App.find_select=find_select
    App.find_next=find_next
    App.find_show=find_show
    App.paint_find=paint_find
    App.render=render
    App.load=load
    App.text_changed=text_changed
